package io.github.dariogguillen.chess.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.dariogguillen.chess.TestcontainersConfiguration;
import io.github.dariogguillen.chess.domain.Game;
import io.github.dariogguillen.chess.domain.GameStatus;
import io.github.dariogguillen.chess.persistence.GameHistoryRepository;
import io.github.dariogguillen.chess.service.GameStore;
import java.lang.reflect.Type;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * End-to-end STOMP integration coverage for feature 11 ({@code disconnect-handling}). The {@code
 * chess.disconnect.grace-period} is overridden to {@code 300ms} via {@link TestPropertySource} so
 * the abandon timer fires well under a second; tests then assert via {@link Awaitility} that the
 * Redis state, Postgres archive, and STOMP broadcast all reflect the abandonment.
 *
 * <p>Three scenarios:
 *
 * <ol>
 *   <li>Disconnect → grace timeout → game ABANDONED, archived, opponent receives {@link
 *       GameAbandonedEvent}.
 *   <li>Disconnect → reconnect within grace → game still ONGOING, no broadcast on either side.
 *   <li>Disconnect on already-terminal game → no second archive, no broadcast.
 * </ol>
 *
 * <p>Setup mirrors {@code GameWebSocketIT} (lines 78-91): {@link WebSocketStompClient} + {@link
 * MappingJackson2MessageConverter} + {@link JavaTimeModule}. The frame handlers read the raw {@code
 * byte[]} payload (rather than a typed payload class) because the topic now carries two shapes
 * ({@link MoveEvent} and {@link GameAbandonedEvent}) and the typed converter would lock the handler
 * to one of them until feature 11.5 adds the {@code type} discriminator.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "chess.disconnect.grace-period=300ms")
class DisconnectHandlingIT {

  /** Override applied via {@link TestPropertySource}. Kept here as a constant so the */
  private static final long GRACE_MS = 300L;

  /** Generic STOMP timeout for "do receive" assertions. */
  private static final long RECEIVE_TIMEOUT_MS = 2_000L;

  /**
   * Brief wait after {@code session.subscribe(...)} returns, before triggering the next server-side
   * action. The subscribe-ack round-trip is asynchronous; without this gap a follow-up disconnect
   * or broadcast may fire before the subscription is registered.
   */
  private static final long SUBSCRIBE_REGISTRATION_DELAY_MS = 1_000L;

  @LocalServerPort private int port;

  @Autowired private ObjectMapper objectMapper;
  @Autowired private GameStore gameStore;
  @Autowired private GameHistoryRepository historyRepository;

  private WebSocketStompClient stompClient;
  private RestTemplate restTemplate;

  @BeforeEach
  void setUp() {
    stompClient = new WebSocketStompClient(new StandardWebSocketClient());
    MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
    ObjectMapper clientMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    converter.setObjectMapper(clientMapper);
    stompClient.setMessageConverter(converter);
    restTemplate = new RestTemplateBuilder().build();
  }

  @AfterEach
  void tearDown() {
    if (stompClient != null) {
      stompClient.stop();
    }
  }

  @Test
  void disconnect_thenTimeout_gameAbandoned_archived_opponentReceivesEvent() throws Exception {
    GameSetup setup = setupGame("Alice", "Bob");

    // White's session: will subscribe with the playerId header, then disconnect to trigger the
    // abandon timer.
    StompSession whiteSession = connect();
    subscribeAsPlayer(whiteSession, setup.gameId(), setup.whitePlayerId());

    // Black's session: subscribes as a player and receives the terminal broadcast on its raw
    // queue.
    StompSession blackSession = connect();
    BlockingQueue<String> blackQueue =
        subscribeAsPlayerWithRawQueue(blackSession, setup.gameId(), setup.blackPlayerId());

    // White disconnects. The server's SessionDisconnectEvent triggers the grace timer; ~GRACE_MS
    // later the timer fires and the abandon path runs.
    whiteSession.disconnect();

    // Wait until the abandon side-effects are visible. The combined budget covers:
    //   - the grace timer
    //   - gameStore.compute() flipping status
    //   - the Postgres archive
    //   - the STOMP broadcast round-trip back to black
    Awaitility.await()
        .atMost(Duration.ofMillis(GRACE_MS + 2_000L))
        .pollInterval(Duration.ofMillis(50))
        .untilAsserted(
            () -> {
              Game persisted = gameStore.findById(setup.gameId()).orElseThrow();
              assertThat(persisted.status()).isEqualTo(GameStatus.ABANDONED);
              assertThat(historyRepository.findById(setup.gameId())).isPresent();
            });

    // The broadcast should also have arrived on black's queue. Drain at most one message — the
    // game has had no moves, so the only event is the GameAbandonedEvent.
    String rawJson = blackQueue.poll(RECEIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertThat(rawJson).isNotNull();
    JsonNode tree = objectMapper.readTree(rawJson);
    assertThat(tree.get("abandonedBy").asText()).isEqualTo(setup.whitePlayerId().toString());
    assertThat(tree.get("winnerId").asText()).isEqualTo(setup.blackPlayerId().toString());
    assertThat(tree.get("gameId").asText()).isEqualTo(setup.gameId().toString());
    // Sanity-check shape: GameAbandonedEvent carries `abandonedBy`/`winnerId` and NOT
    // `from`/`to` — the frontend uses this exact shape difference to discriminate from MoveEvent
    // until feature 11.5 adds the explicit `type` field.
    assertThat(tree.get("from")).isNull();
    assertThat(tree.get("to")).isNull();
  }

  @Test
  void disconnect_thenReconnectWithinGrace_gameStillActive_noBroadcast() throws Exception {
    GameSetup setup = setupGame("Alice", "Bob");

    StompSession whiteSession = connect();
    subscribeAsPlayer(whiteSession, setup.gameId(), setup.whitePlayerId());

    StompSession blackSession = connect();
    BlockingQueue<String> blackQueue =
        subscribeAsPlayerWithRawQueue(blackSession, setup.gameId(), setup.blackPlayerId());

    whiteSession.disconnect();

    // Reconnect well within the grace window. The new SUBSCRIBE with the same playerId cancels
    // the pending timer.
    StompSession whiteReconnect = connect();
    subscribeAsPlayer(whiteReconnect, setup.gameId(), setup.whitePlayerId());

    // Wait past the original grace deadline and assert the game is still ONGOING.
    Thread.sleep(GRACE_MS + 800L);

    Game persisted = gameStore.findById(setup.gameId()).orElseThrow();
    assertThat(persisted.status()).isEqualTo(GameStatus.ONGOING);
    assertThat(historyRepository.findById(setup.gameId())).isEmpty();
    assertThat(blackQueue.poll(100, TimeUnit.MILLISECONDS)).isNull();
  }

  @Test
  void disconnect_onAlreadyTerminalGame_noOp() throws Exception {
    GameSetup setup = setupGame("Alice", "Bob");

    StompSession whiteSession = connect();
    subscribeAsPlayer(whiteSession, setup.gameId(), setup.whitePlayerId());
    StompSession blackSession = connect();
    BlockingQueue<String> blackQueue =
        subscribeAsPlayerWithRawQueue(blackSession, setup.gameId(), setup.blackPlayerId());

    // Fool's Mate: 1. f2-f3 e7-e5 2. g2-g4 d8-h4# — the shortest checkmate. Mirrors the sequence
    // in GameControllerIT.
    applyMove(setup.gameId(), setup.whitePlayerId(), "f2", "f3");
    applyMove(setup.gameId(), setup.blackPlayerId(), "e7", "e5");
    applyMove(setup.gameId(), setup.whitePlayerId(), "g2", "g4");
    applyMove(setup.gameId(), setup.blackPlayerId(), "d8", "h4");

    // The CHECKMATE archive from GameService.applyMove has already happened. Drain the four
    // MoveEvent broadcasts black just received so the next assertion polls a clean queue.
    for (int i = 0; i < 4; i++) {
      assertThat(blackQueue.poll(RECEIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS))
          .as("expected MoveEvent #%d", i)
          .isNotNull();
    }

    long archiveCountBeforeDisconnect = historyRepository.count();

    whiteSession.disconnect();

    Thread.sleep(GRACE_MS + 800L);

    // Status is still CHECKMATE — no second mutation.
    Game persisted = gameStore.findById(setup.gameId()).orElseThrow();
    assertThat(persisted.status()).isEqualTo(GameStatus.CHECKMATE);
    // No second archive — count is unchanged.
    assertThat(historyRepository.count()).isEqualTo(archiveCountBeforeDisconnect);
    // No GameAbandonedEvent broadcast on the topic.
    String stray = blackQueue.poll(100, TimeUnit.MILLISECONDS);
    assertThat(stray).isNull();
  }

  private StompSession connect() throws Exception {
    return stompClient
        .connectAsync("ws://localhost:" + port + "/ws", new StompSessionHandlerAdapter() {})
        .get(RECEIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
  }

  /**
   * Subscribes to {@code /topic/games/{gameId}} with the player's id sent as a native STOMP header,
   * but discards inbound payloads. Used by the "white" session when the test only cares about the
   * side effect of the subscribe (and the eventual disconnect).
   */
  private void subscribeAsPlayer(StompSession session, UUID gameId, UUID playerId)
      throws InterruptedException {
    StompHeaders headers = new StompHeaders();
    headers.setDestination("/topic/games/" + gameId);
    headers.add("playerId", playerId.toString());
    session.subscribe(
        headers,
        new StompFrameHandler() {
          @Override
          public Type getPayloadType(StompHeaders frameHeaders) {
            return byte[].class;
          }

          @Override
          public void handleFrame(StompHeaders frameHeaders, Object payload) {
            // Intentionally discarded.
          }
        });
    Thread.sleep(SUBSCRIBE_REGISTRATION_DELAY_MS);
  }

  /**
   * Subscribes to {@code /topic/games/{gameId}} with the player's id sent as a native STOMP header
   * and queues every incoming payload as UTF-8 JSON. The raw-byte path keeps the test agnostic to
   * the two event shapes ({@link MoveEvent} and {@link GameAbandonedEvent}) that share the topic
   * until feature 11.5 adds the discriminator.
   */
  private BlockingQueue<String> subscribeAsPlayerWithRawQueue(
      StompSession session, UUID gameId, UUID playerId) throws InterruptedException {
    BlockingQueue<String> queue = new ArrayBlockingQueue<>(16);
    StompHeaders headers = new StompHeaders();
    headers.setDestination("/topic/games/" + gameId);
    headers.add("playerId", playerId.toString());
    session.subscribe(
        headers,
        new StompFrameHandler() {
          @Override
          public Type getPayloadType(StompHeaders frameHeaders) {
            return byte[].class;
          }

          @Override
          public void handleFrame(StompHeaders frameHeaders, Object payload) {
            queue.offer(new String((byte[]) payload, StandardCharsets.UTF_8));
          }
        });
    Thread.sleep(SUBSCRIBE_REGISTRATION_DELAY_MS);
    return queue;
  }

  private void applyMove(UUID gameId, UUID playerId, String from, String to) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("X-Player-Id", playerId.toString());
    String body = "{\"from\":\"" + from + "\",\"to\":\"" + to + "\"}";
    restTemplate.exchange(
        baseUrl() + "/api/games/" + gameId + "/moves",
        HttpMethod.POST,
        new HttpEntity<>(body, headers),
        String.class);
  }

  private GameSetup setupGame(String whiteName, String blackName) throws Exception {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);

    ResponseEntity<String> createResponse =
        restTemplate.exchange(
            baseUrl() + "/api/rooms",
            HttpMethod.POST,
            new HttpEntity<>("{\"displayName\":\"" + whiteName + "\"}", headers),
            String.class);
    JsonNode createBody = objectMapper.readTree(createResponse.getBody());
    String roomId = createBody.get("roomId").asText();
    UUID whitePlayerId = UUID.fromString(createBody.get("playerId").asText());

    ResponseEntity<String> joinResponse =
        restTemplate.exchange(
            baseUrl() + "/api/rooms/" + roomId + "/join",
            HttpMethod.POST,
            new HttpEntity<>("{\"displayName\":\"" + blackName + "\"}", headers),
            String.class);
    JsonNode joinBody = objectMapper.readTree(joinResponse.getBody());
    UUID gameId = UUID.fromString(joinBody.get("gameId").asText());
    UUID blackPlayerId = UUID.fromString(joinBody.get("playerId").asText());

    return new GameSetup(gameId, whitePlayerId, blackPlayerId);
  }

  private String baseUrl() {
    return URI.create("http://localhost:" + port).toString();
  }

  private record GameSetup(UUID gameId, UUID whitePlayerId, UUID blackPlayerId) {}
}
