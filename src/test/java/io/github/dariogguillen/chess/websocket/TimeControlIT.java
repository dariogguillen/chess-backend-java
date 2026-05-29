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
import io.github.dariogguillen.chess.service.GameTimeoutService;
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
import org.springframework.web.client.RestTemplate;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * End-to-end coverage for feature 22 ({@code time-control}). A timed room is created with a tiny
 * {@code initialMs} (≈300ms) so the real {@link org.springframework.scheduling.TaskScheduler} fires
 * the per-game flag timer well under a second — the same short-timer idiom {@code
 * DisconnectHandlingIT} uses for the 300ms grace period. With no moves made, white's clock runs out
 * and the server auto-flags the game: status {@link GameStatus#TIMEOUT}, archived to Postgres, and
 * a {@link GameTimedOutEvent} delivered on {@code /topic/games/{gameId}}.
 *
 * <p>The frame handler reads the raw {@code byte[]} payload (rather than a typed class) because the
 * topic is polymorphic; the test discriminates on the {@code type} field.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TimeControlIT {

  /**
   * A short initial clock so the flag fires fast on wall-clock (the grace-period idiom). Kept above
   * the subscribe-registration delay so the "black" subscriber is registered on the topic before
   * white's flag fires — otherwise the broadcast would land before there is anyone listening.
   */
  private static final long INITIAL_MS = 2_000L;

  private static final long RECEIVE_TIMEOUT_MS = 2_000L;

  /**
   * Brief wait after {@code subscribe(...)} returns, before the flag is expected, so the subscribe
   * ack round-trip has registered the destination before the broadcast lands.
   */
  private static final long SUBSCRIBE_REGISTRATION_DELAY_MS = 1_000L;

  @LocalServerPort private int port;

  @Autowired private ObjectMapper objectMapper;
  @Autowired private GameStore gameStore;
  @Autowired private GameHistoryRepository historyRepository;
  @Autowired private GameTimeoutService gameTimeoutService;

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
  void timedGame_noMoves_autoFlagsTimeout_archived_andBroadcasts() throws Exception {
    GameSetup setup = setupTimedGame("Alice", "Bob");

    StompSession blackSession = connect();
    BlockingQueue<String> blackQueue = subscribeWithRawQueue(blackSession, setup.gameId());

    // Make no moves. White's ≈300ms clock runs out; the flag timer fires and flags the game.
    Awaitility.await()
        .atMost(Duration.ofMillis(INITIAL_MS + 2_500L))
        .pollInterval(Duration.ofMillis(50))
        .untilAsserted(
            () -> {
              Game persisted = gameStore.findById(setup.gameId()).orElseThrow();
              assertThat(persisted.status()).isEqualTo(GameStatus.TIMEOUT);
              assertThat(historyRepository.findById(setup.gameId())).isPresent();
            });

    String rawJson = blackQueue.poll(RECEIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertThat(rawJson).isNotNull();
    JsonNode tree = objectMapper.readTree(rawJson);
    assertThat(tree.get("type").asText()).isEqualTo("GAME_TIMED_OUT");
    assertThat(tree.get("gameId").asText()).isEqualTo(setup.gameId().toString());
    // White was to move and ran out — white is the timed-out side, black wins on time.
    assertThat(tree.get("timedOutSide").asText()).isEqualTo("WHITE");
    assertThat(tree.get("winnerId").asText()).isEqualTo(setup.blackPlayerId().toString());
    assertThat(tree.get("whiteTimeRemainingMs").asLong()).isZero();
  }

  @Test
  void timeoutOnAlreadyTerminalGame_isNoOp() throws Exception {
    GameSetup setup = setupTimedGame("Alice", "Bob");

    // Wait for the natural flag to land the game in TIMEOUT and archive it.
    Awaitility.await()
        .atMost(Duration.ofMillis(INITIAL_MS + 2_500L))
        .pollInterval(Duration.ofMillis(50))
        .untilAsserted(
            () ->
                assertThat(gameStore.findById(setup.gameId()).orElseThrow().status())
                    .isEqualTo(GameStatus.TIMEOUT));

    long archiveCountBefore = historyRepository.count();

    // A second flag fire on the already-terminal game must be a no-op: no status change, no second
    // archive.
    gameTimeoutService.timeout(setup.gameId());

    Game persisted = gameStore.findById(setup.gameId()).orElseThrow();
    assertThat(persisted.status()).isEqualTo(GameStatus.TIMEOUT);
    assertThat(historyRepository.count()).isEqualTo(archiveCountBefore);
  }

  private StompSession connect() throws Exception {
    return stompClient
        .connectAsync("ws://localhost:" + port + "/ws", new StompSessionHandlerAdapter() {})
        .get(RECEIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
  }

  private BlockingQueue<String> subscribeWithRawQueue(StompSession session, UUID gameId)
      throws InterruptedException {
    BlockingQueue<String> queue = new ArrayBlockingQueue<>(16);
    StompHeaders headers = new StompHeaders();
    headers.setDestination("/topic/games/" + gameId);
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

  private GameSetup setupTimedGame(String whiteName, String blackName) throws Exception {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);

    String createBody =
        "{\"displayName\":\""
            + whiteName
            + "\",\"timeControl\":{\"initialMs\":"
            + INITIAL_MS
            + ",\"incrementMs\":0}}";
    ResponseEntity<String> createResponse =
        restTemplate.exchange(
            baseUrl() + "/api/rooms",
            HttpMethod.POST,
            new HttpEntity<>(createBody, headers),
            String.class);
    JsonNode create = objectMapper.readTree(createResponse.getBody());
    String roomId = create.get("roomId").asText();
    String joinToken = create.get("joinToken").asText();
    UUID whitePlayerId = UUID.fromString(create.get("playerId").asText());

    ResponseEntity<String> joinResponse =
        restTemplate.exchange(
            baseUrl() + "/api/rooms/" + roomId + "/join",
            HttpMethod.POST,
            new HttpEntity<>(
                "{\"displayName\":\"" + blackName + "\",\"joinToken\":\"" + joinToken + "\"}",
                headers),
            String.class);
    JsonNode join = objectMapper.readTree(joinResponse.getBody());
    UUID gameId = UUID.fromString(join.get("gameId").asText());
    UUID blackPlayerId = UUID.fromString(join.get("playerId").asText());

    return new GameSetup(gameId, whitePlayerId, blackPlayerId);
  }

  private String baseUrl() {
    return URI.create("http://localhost:" + port).toString();
  }

  private record GameSetup(UUID gameId, UUID whitePlayerId, UUID blackPlayerId) {}
}
