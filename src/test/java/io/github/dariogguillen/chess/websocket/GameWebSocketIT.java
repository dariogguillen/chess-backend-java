package io.github.dariogguillen.chess.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.dariogguillen.chess.TestcontainersConfiguration;
import io.github.dariogguillen.chess.domain.GameStatus;
import io.github.dariogguillen.chess.domain.Side;
import java.lang.reflect.Type;
import java.net.URI;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * Integration tests for the STOMP broadcast surface added in feature 6. Boots the full Spring
 * context on a random port (we need a real WebSocket handshake against a real connector, which
 * {@code MockMvc} does not provide), connects a {@link WebSocketStompClient} to {@code /ws}, and
 * asserts that after a successful REST {@code POST /api/games/{id}/moves}, subscribers to {@code
 * /topic/games/{gameId}} receive a {@link MoveEvent}. Negative paths assert no broadcast happens
 * when the underlying mutation is rejected (illegal move, wrong player) or targets a different
 * game.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GameWebSocketIT {

  /** Timeout for assertions that DO expect to receive a MoveEvent. */
  private static final long RECEIVE_TIMEOUT_MS = 2_000L;

  /**
   * Timeout for assertions that expect NO MoveEvent. Short — we want the negative tests to be
   * cheap, but long enough that a stray slow broadcast would still be observable.
   */
  private static final long NO_RECEIVE_TIMEOUT_MS = 500L;

  /**
   * Brief wait after {@code session.subscribe(...)} returns, before triggering the REST move. The
   * subscribe-ack round-trip is asynchronous; without this gap the server-side broadcast can fire
   * before the subscription is registered and the test misses the frame.
   */
  private static final long SUBSCRIBE_REGISTRATION_DELAY_MS = 1_000L;

  @LocalServerPort private int port;

  @Autowired private ObjectMapper objectMapper;

  private WebSocketStompClient stompClient;
  private RestTemplate restTemplate;

  @BeforeEach
  void setUp() {
    stompClient = new WebSocketStompClient(new StandardWebSocketClient());
    MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
    // The default ObjectMapper inside MappingJackson2MessageConverter does not register
    // JavaTimeModule, so deserializing the MoveEvent.playedAt Instant would otherwise fail
    // silently inside the StompFrameHandler. The Spring Boot autoconfigured ObjectMapper does
    // include the module, but the STOMP converter ships with its own instance — we configure it
    // explicitly here.
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
  void singleSubscriber_receivesMoveEvent_afterSuccessfulMove() throws Exception {
    GameSetup setup = setupGame("Alice", "Bob");
    StompSession session = connect();
    BlockingQueue<MoveEvent> queue = subscribe(session, setup.gameId());

    ResponseEntity<String> response = applyMove(setup.gameId(), setup.whitePlayerId(), "e2", "e4");
    assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();

    MoveEvent received = queue.poll(RECEIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertThat(received).isNotNull();
    assertThat(received.gameId()).isEqualTo(setup.gameId());
    assertThat(received.movedBy()).isEqualTo(setup.whitePlayerId());
    assertThat(received.side()).isEqualTo(Side.WHITE);
    assertThat(received.from()).isEqualTo("e2");
    assertThat(received.to()).isEqualTo("e4");
    assertThat(received.promotion()).isNull();
    assertThat(received.fen()).isNotBlank();
    assertThat(received.status()).isEqualTo(GameStatus.ONGOING);
    assertThat(received.turn()).isEqualTo(Side.BLACK);
    assertThat(received.moveNumber()).isEqualTo(1);
    assertThat(received.playedAt()).isNotNull();
    // Feature 22: an untimed game carries null clock fields on the MoveEvent.
    assertThat(received.whiteTimeRemainingMs()).isNull();
    assertThat(received.blackTimeRemainingMs()).isNull();
  }

  @Test
  void timedGame_moveEventCarriesClockFields() throws Exception {
    // A generous 10-minute initial so the flag timer does not fire during the test.
    GameSetup setup = setupTimedGame("Alice", "Bob", 600_000L, 3_000L);
    StompSession session = connect();
    BlockingQueue<MoveEvent> queue = subscribe(session, setup.gameId());

    applyMove(setup.gameId(), setup.whitePlayerId(), "e2", "e4");

    MoveEvent received = queue.poll(RECEIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertThat(received).isNotNull();
    assertThat(received.whiteTimeRemainingMs()).isNotNull();
    assertThat(received.blackTimeRemainingMs()).isNotNull();
    // White just moved: their clock decreased from the 600000ms initial (minus elapsed, plus
    // the 3000ms increment); the upper bound is initial + increment. Black is untouched.
    assertThat(received.whiteTimeRemainingMs()).isLessThanOrEqualTo(603_000L);
    assertThat(received.blackTimeRemainingMs()).isEqualTo(600_000L);
  }

  @Test
  void twoSubscribers_bothReceiveMoveEvent() throws Exception {
    GameSetup setup = setupGame("Alice", "Bob");
    StompSession sessionA = connect();
    StompSession sessionB = connect();
    BlockingQueue<MoveEvent> queueA = subscribe(sessionA, setup.gameId());
    BlockingQueue<MoveEvent> queueB = subscribe(sessionB, setup.gameId());

    applyMove(setup.gameId(), setup.whitePlayerId(), "e2", "e4");

    MoveEvent receivedA = queueA.poll(RECEIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    MoveEvent receivedB = queueB.poll(RECEIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertThat(receivedA).isNotNull();
    assertThat(receivedB).isNotNull();
    assertThat(receivedA.gameId()).isEqualTo(setup.gameId());
    assertThat(receivedB.gameId()).isEqualTo(setup.gameId());
    assertThat(receivedA.from()).isEqualTo("e2");
    assertThat(receivedB.from()).isEqualTo("e2");
    assertThat(receivedA.to()).isEqualTo("e4");
    assertThat(receivedB.to()).isEqualTo("e4");
    assertThat(receivedA.moveNumber()).isEqualTo(receivedB.moveNumber());
    assertThat(receivedA.fen()).isEqualTo(receivedB.fen());
  }

  @Test
  void subscribingToOtherGame_doesNotReceiveBroadcast() throws Exception {
    GameSetup setupA = setupGame("Alice", "Bob");
    GameSetup setupB = setupGame("Carol", "Dave");
    StompSession session = connect();
    BlockingQueue<MoveEvent> queueA = subscribe(session, setupA.gameId());

    // Move happens on game B; subscriber listens to game A.
    applyMove(setupB.gameId(), setupB.whitePlayerId(), "e2", "e4");

    MoveEvent leak = queueA.poll(NO_RECEIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertThat(leak).isNull();
  }

  @Test
  void illegalMove_doesNotBroadcast() throws Exception {
    GameSetup setup = setupGame("Alice", "Bob");
    StompSession session = connect();
    BlockingQueue<MoveEvent> queue = subscribe(session, setup.gameId());

    // e2-e5 is structurally valid but illegal in chess (a pawn cannot jump 3 squares).
    ResponseEntity<String> response = applyMove(setup.gameId(), setup.whitePlayerId(), "e2", "e5");
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

    MoveEvent leak = queue.poll(NO_RECEIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertThat(leak).isNull();
  }

  @Test
  void moveByWrongPlayer_doesNotBroadcast() throws Exception {
    GameSetup setup = setupGame("Alice", "Bob");
    StompSession session = connect();
    BlockingQueue<MoveEvent> queue = subscribe(session, setup.gameId());

    // Black tries to move first; expected 422 NOT_YOUR_TURN.
    ResponseEntity<String> response = applyMove(setup.gameId(), setup.blackPlayerId(), "e7", "e5");
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

    MoveEvent leak = queue.poll(NO_RECEIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertThat(leak).isNull();
  }

  private StompSession connect() throws Exception {
    return stompClient
        .connectAsync("ws://localhost:" + port + "/ws", new StompSessionHandlerAdapter() {})
        .get(RECEIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
  }

  private BlockingQueue<MoveEvent> subscribe(StompSession session, UUID gameId)
      throws InterruptedException {
    BlockingQueue<MoveEvent> queue = new ArrayBlockingQueue<>(8);
    session.subscribe(
        "/topic/games/" + gameId,
        new StompFrameHandler() {
          @Override
          public Type getPayloadType(StompHeaders headers) {
            return MoveEvent.class;
          }

          @Override
          public void handleFrame(StompHeaders headers, Object payload) {
            queue.offer((MoveEvent) payload);
          }
        });
    // Subscribe ack is asynchronous; give the broker a moment to register the destination.
    Thread.sleep(SUBSCRIBE_REGISTRATION_DELAY_MS);
    return queue;
  }

  /**
   * Posts a move via REST. Returns the {@link ResponseEntity} so the caller can assert on the
   * status code (the WebSocket test cares about both 2xx and 4xx responses). Uses a custom error
   * handler so 4xx responses do not throw — the negative tests need to read them.
   */
  private ResponseEntity<String> applyMove(UUID gameId, UUID playerId, String from, String to) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("X-Player-Id", playerId.toString());
    String body = "{\"from\":\"" + from + "\",\"to\":\"" + to + "\"}";
    try {
      return restTemplate.exchange(
          baseUrl() + "/api/games/" + gameId + "/moves",
          HttpMethod.POST,
          new HttpEntity<>(body, headers),
          String.class);
    } catch (HttpStatusCodeException ex) {
      HttpStatusCode status = ex.getStatusCode();
      return ResponseEntity.status(status).body(ex.getResponseBodyAsString());
    }
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

  /**
   * Creates a timed room (declared {@code timeControl}) and joins it, returning the game/player
   * ids. Mirrors {@link #setupGame(String, String)} but threads a {@code timeControl} object into
   * the create body.
   */
  private GameSetup setupTimedGame(
      String whiteName, String blackName, long initialMs, long incrementMs) throws Exception {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);

    String createBody =
        "{\"displayName\":\""
            + whiteName
            + "\",\"timeControl\":{\"initialMs\":"
            + initialMs
            + ",\"incrementMs\":"
            + incrementMs
            + "}}";
    ResponseEntity<String> createResponse =
        restTemplate.exchange(
            baseUrl() + "/api/rooms",
            HttpMethod.POST,
            new HttpEntity<>(createBody, headers),
            String.class);
    JsonNode create = objectMapper.readTree(createResponse.getBody());
    String roomId = create.get("roomId").asText();
    UUID whitePlayerId = UUID.fromString(create.get("playerId").asText());

    ResponseEntity<String> joinResponse =
        restTemplate.exchange(
            baseUrl() + "/api/rooms/" + roomId + "/join",
            HttpMethod.POST,
            new HttpEntity<>("{\"displayName\":\"" + blackName + "\"}", headers),
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
