package io.github.dariogguillen.chess.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.dariogguillen.chess.TestcontainersConfiguration;
import java.lang.reflect.Type;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
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
 * End-to-end STOMP integration coverage for feature 11.5 ({@code disconnect-notifications}). The
 * {@code chess.disconnect.grace-period} is overridden to {@code 2s} via {@link TestPropertySource}
 * so the disconnect-broadcast / reconnect-broadcast / grace-fires sequence stays well under the 30s
 * test budget while still leaving enough room for a same-process reconnect to land inside the
 * window.
 *
 * <p>Three scenarios:
 *
 * <ol>
 *   <li>Disconnect → opponent receives a {@link PlayerDisconnectedEvent} promptly, then the {@link
 *       GameAbandonedEvent} after the grace window elapses (sanity check on event ordering on the
 *       topic).
 *   <li>Disconnect → reconnect within grace → opponent receives a {@link PlayerDisconnectedEvent}
 *       and then a {@link PlayerReconnectedEvent}; no {@link GameAbandonedEvent} arrives.
 *   <li>Disconnect on a game already in CHECKMATE → no {@link PlayerDisconnectedEvent} broadcast,
 *       no second archive (the terminal-game guard from feature 11 still holds).
 * </ol>
 *
 * <p>The frame handlers read raw {@code byte[]} payload (rather than a typed payload class) because
 * the topic carries four discriminated event variants after this feature ({@link MoveEvent}, {@link
 * GameAbandonedEvent}, {@link PlayerDisconnectedEvent}, {@link PlayerReconnectedEvent}) and the
 * typed converter would lock the handler to one of them. Tests parse each frame with Jackson and
 * branch on the {@code type} discriminator field — the same shape a real frontend consumes.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "chess.disconnect.grace-period=2s")
class DisconnectNotificationsIT {

  /** Override applied via {@link TestPropertySource}. Kept here as a constant so the */
  private static final long GRACE_MS = 2_000L;

  /** Generic STOMP timeout for "do receive" assertions. */
  private static final long RECEIVE_TIMEOUT_MS = 2_000L;

  /**
   * Brief wait after {@code session.subscribe(...)} returns, before triggering the next server-side
   * action. The subscribe-ack round-trip is asynchronous; without this gap a follow-up disconnect
   * or broadcast may fire before the subscription is registered.
   */
  private static final long SUBSCRIBE_REGISTRATION_DELAY_MS = 1_000L;

  /**
   * Tolerance window for the {@code gracePeriodEndsAt} computation. The server's {@code
   * Instant.now(clock) + gracePeriod} happens slightly after the client measures "now", so the
   * actual deadline lands a bit after {@code clientNow + grace}. Half the grace is a comfortable
   * lower bound; one and a half graces is the upper bound for slow CI.
   */
  private static final Duration LOWER_DEADLINE_TOLERANCE = Duration.ofMillis(GRACE_MS / 2);

  private static final Duration UPPER_DEADLINE_TOLERANCE = Duration.ofMillis(GRACE_MS * 3 / 2);

  @LocalServerPort private int port;

  @Autowired private ObjectMapper objectMapper;

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
  void disconnect_broadcastsPlayerDisconnectedEvent_thenGameAbandonedEvent() throws Exception {
    GameSetup setup = setupGame("Alice", "Bob");

    // White subscribes as a player; the disconnect below triggers the grace timer.
    StompSession whiteSession = connect();
    subscribeAsPlayer(whiteSession, setup.gameId(), setup.whitePlayerId());

    // Black subscribes as the observer for the broadcasts.
    StompSession blackSession = connect();
    BlockingQueue<String> blackQueue =
        subscribeAsPlayerWithRawQueue(blackSession, setup.gameId(), setup.blackPlayerId());

    Instant beforeDisconnect = Instant.now();
    whiteSession.disconnect();

    // First frame: PLAYER_DISCONNECTED. Arrives well within the grace window — the broadcast is
    // synchronous on the server side relative to the SessionDisconnectEvent.
    JsonNode disconnectFrame = pollOrFail(blackQueue, "PLAYER_DISCONNECTED frame");
    assertThat(disconnectFrame.get("type").asText()).isEqualTo("PLAYER_DISCONNECTED");
    assertThat(disconnectFrame.get("gameId").asText()).isEqualTo(setup.gameId().toString());
    assertThat(disconnectFrame.get("playerId").asText())
        .isEqualTo(setup.whitePlayerId().toString());
    assertThat(disconnectFrame.get("side").asText()).isEqualTo("WHITE");
    assertThat(disconnectFrame.has("disconnectedAt")).isTrue();
    assertThat(disconnectFrame.has("gracePeriodEndsAt")).isTrue();

    Instant gracePeriodEndsAt = Instant.parse(disconnectFrame.get("gracePeriodEndsAt").asText());
    Instant lowerBound = beforeDisconnect.plus(LOWER_DEADLINE_TOLERANCE);
    Instant upperBound = beforeDisconnect.plus(UPPER_DEADLINE_TOLERANCE);
    assertThat(gracePeriodEndsAt)
        .as(
            "gracePeriodEndsAt should land within +%d..+%dms of disconnect",
            LOWER_DEADLINE_TOLERANCE.toMillis(), UPPER_DEADLINE_TOLERANCE.toMillis())
        .isAfterOrEqualTo(lowerBound)
        .isBeforeOrEqualTo(upperBound);

    // Second frame, after the grace timer fires: GAME_ABANDONED. The PlayerDisconnectedEvent
    // arrives before the GameAbandonedEvent because the broker preserves order from a single
    // publisher process (Spring SimpleBroker, single instance).
    JsonNode abandonedFrame =
        pollOrFail(blackQueue, "GAME_ABANDONED frame", GRACE_MS + RECEIVE_TIMEOUT_MS);
    assertThat(abandonedFrame.get("type").asText()).isEqualTo("GAME_ABANDONED");
    assertThat(abandonedFrame.get("gameId").asText()).isEqualTo(setup.gameId().toString());
    assertThat(abandonedFrame.get("abandonedBy").asText())
        .isEqualTo(setup.whitePlayerId().toString());
    assertThat(abandonedFrame.get("winnerId").asText()).isEqualTo(setup.blackPlayerId().toString());
  }

  @Test
  void reconnectWithinGrace_broadcastsPlayerReconnectedEvent_noAbandon() throws Exception {
    GameSetup setup = setupGame("Alice", "Bob");

    StompSession whiteSession = connect();
    subscribeAsPlayer(whiteSession, setup.gameId(), setup.whitePlayerId());

    StompSession blackSession = connect();
    BlockingQueue<String> blackQueue =
        subscribeAsPlayerWithRawQueue(blackSession, setup.gameId(), setup.blackPlayerId());

    whiteSession.disconnect();

    // First frame: PLAYER_DISCONNECTED.
    JsonNode disconnectFrame = pollOrFail(blackQueue, "PLAYER_DISCONNECTED frame");
    assertThat(disconnectFrame.get("type").asText()).isEqualTo("PLAYER_DISCONNECTED");
    assertThat(disconnectFrame.get("side").asText()).isEqualTo("WHITE");

    // White reconnects well within the grace window with the same playerId header — the
    // SUBSCRIBE-side handler cancels the pending timer and broadcasts PLAYER_RECONNECTED.
    StompSession whiteReconnect = connect();
    subscribeAsPlayer(whiteReconnect, setup.gameId(), setup.whitePlayerId());

    JsonNode reconnectFrame = pollOrFail(blackQueue, "PLAYER_RECONNECTED frame");
    assertThat(reconnectFrame.get("type").asText()).isEqualTo("PLAYER_RECONNECTED");
    assertThat(reconnectFrame.get("gameId").asText()).isEqualTo(setup.gameId().toString());
    assertThat(reconnectFrame.get("playerId").asText()).isEqualTo(setup.whitePlayerId().toString());
    assertThat(reconnectFrame.get("side").asText()).isEqualTo("WHITE");
    assertThat(reconnectFrame.has("reconnectedAt")).isTrue();

    // Wait past the original grace deadline; no GAME_ABANDONED should land because the timer was
    // cancelled.
    Thread.sleep(GRACE_MS + 800L);
    String stray = blackQueue.poll(100, TimeUnit.MILLISECONDS);
    assertThat(stray).as("no further frame after PLAYER_RECONNECTED").isNull();
  }

  @Test
  void disconnect_onAlreadyTerminalGame_noPlayerDisconnectedBroadcast() throws Exception {
    GameSetup setup = setupGame("Alice", "Bob");

    StompSession whiteSession = connect();
    subscribeAsPlayer(whiteSession, setup.gameId(), setup.whitePlayerId());
    StompSession blackSession = connect();
    BlockingQueue<String> blackQueue =
        subscribeAsPlayerWithRawQueue(blackSession, setup.gameId(), setup.blackPlayerId());

    // Fool's Mate: 1. f2-f3 e7-e5 2. g2-g4 d8-h4# — the shortest checkmate. Mirrors the sequence
    // in DisconnectHandlingIT.
    applyMove(setup.gameId(), setup.whitePlayerId(), "f2", "f3");
    applyMove(setup.gameId(), setup.blackPlayerId(), "e7", "e5");
    applyMove(setup.gameId(), setup.whitePlayerId(), "g2", "g4");
    applyMove(setup.gameId(), setup.blackPlayerId(), "d8", "h4");

    // Drain the four MoveEvent broadcasts. Each carries `type=MOVE` after the retrofit.
    for (int i = 0; i < 4; i++) {
      String raw = blackQueue.poll(RECEIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
      assertThat(raw).as("expected MOVE frame #%d", i).isNotNull();
      JsonNode tree = objectMapper.readTree(raw);
      assertThat(tree.get("type").asText()).isEqualTo("MOVE");
    }

    // Disconnect white on the already-CHECKMATE game. The terminal-game guard in
    // PlayerSessionTracker.onDisconnect must skip BOTH the grace timer AND the
    // PlayerDisconnectedEvent broadcast.
    whiteSession.disconnect();

    Thread.sleep(GRACE_MS + 800L);

    String stray = blackQueue.poll(100, TimeUnit.MILLISECONDS);
    assertThat(stray).as("no PLAYER_DISCONNECTED or GAME_ABANDONED on a terminal game").isNull();
  }

  private JsonNode pollOrFail(BlockingQueue<String> queue, String description) throws Exception {
    return pollOrFail(queue, description, RECEIVE_TIMEOUT_MS);
  }

  private JsonNode pollOrFail(BlockingQueue<String> queue, String description, long timeoutMs)
      throws Exception {
    String raw = queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
    assertThat(raw).as("expected %s within %dms", description, timeoutMs).isNotNull();
    return objectMapper.readTree(raw);
  }

  private StompSession connect() throws Exception {
    return stompClient
        .connectAsync("ws://localhost:" + port + "/ws", new StompSessionHandlerAdapter() {})
        .get(RECEIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
  }

  /**
   * Subscribes to {@code /topic/games/{gameId}} with the player's id sent as a native STOMP header,
   * discarding inbound payloads. Used by the "white" session when the test only cares about the
   * side effect of the subscribe (and the eventual disconnect / reconnect).
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
   * the four event shapes ({@link MoveEvent}, {@link GameAbandonedEvent}, {@link
   * PlayerDisconnectedEvent}, {@link PlayerReconnectedEvent}) that share the topic.
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
