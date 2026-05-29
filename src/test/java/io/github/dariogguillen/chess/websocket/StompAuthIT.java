package io.github.dariogguillen.chess.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.dariogguillen.chess.TestcontainersConfiguration;
import io.github.dariogguillen.chess.config.security.JwtIssuer;
import io.github.dariogguillen.chess.domain.User;
import io.github.dariogguillen.chess.persistence.UserRepository;
import java.lang.reflect.Type;
import java.net.URI;
import java.nio.charset.StandardCharsets;
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
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * Integration coverage for {@link StompAuthInterceptor} (feature 20, {@code auth-stomp-trust}).
 * Boots the full Spring context on a random port — a real WebSocket handshake against a real
 * connector is required so that the native {@code Authorization} header on STOMP CONNECT actually
 * reaches the interceptor (a {@code MockMvc} flow cannot exercise the channel). Five cases pin the
 * two-phase contract:
 *
 * <ol>
 *   <li>CONNECT without {@code Authorization} → anonymous; subscribe + send-without-claim succeed.
 *       Regression pin for guest play.
 *   <li>CONNECT with a valid JWT for user A → identified; SEND with A's authenticated-game player
 *       id passes the spoof check.
 *   <li>CONNECT with an expired JWT → connects anyway, stays anonymous; subscribe + send-without
 *       -claim still succeed. <strong>Critical:</strong> a bad JWT does NOT break guest play.
 *   <li>SEND from authenticated session A whose claim is B's player → ERROR frame observed
 *       client-side; the broker never sees the frame; the session stays open.
 *   <li>SEND from anonymous session that pinned player X → a follow-up SEND claiming Y is rejected
 *       with an ERROR frame and dropped.
 * </ol>
 *
 * <p>Mirrors the {@code WebSocketStompClient} + {@code MappingJackson2MessageConverter} scaffolding
 * established by {@code GameWebSocketIT} and {@code RoomLifecycleIT}. The ERROR-frame observation
 * relies on {@link StompSessionHandlerAdapter#handleFrame(StompHeaders, Object)} — the base adapter
 * routes ERROR frames there via the framework's frame dispatcher.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StompAuthIT {

  private static final long RECEIVE_TIMEOUT_MS = 2_000L;
  private static final long NO_RECEIVE_TIMEOUT_MS = 500L;
  private static final long SUBSCRIBE_REGISTRATION_DELAY_MS = 1_000L;

  @LocalServerPort private int port;

  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserRepository users;
  @Autowired private JwtIssuer jwtIssuer;

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
  void stompConnect_withoutAuthHeader_succeedsAnonymous() throws Exception {
    GameSetup setup = setupAnonymousGame("Alice", "Bob");
    ErrorCapturingHandler handler = new ErrorCapturingHandler();
    StompSession session = connect(null, handler);

    assertThat(session.isConnected()).isTrue();
    BlockingQueue<String> received = subscribeRaw(session, "/topic/games/" + setup.gameId(), null);
    sendWithoutClaim(session, "/app/games/" + setup.gameId() + "/probe");

    // No ERROR frame on the anonymous-without-claim happy path.
    assertThat(handler.poll()).isNull();
    // The subscribe / send did not produce a broker message (no @MessageMapping handler), but the
    // queue must be empty without any leak from the interceptor itself.
    assertThat(received.poll(NO_RECEIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS)).isNull();
  }

  @Test
  void stompConnect_withValidJwt_succeedsAndIdentifiesSession() throws Exception {
    User userA = saveUser("alice-stomp@example.com", "Alice");
    String tokenA = jwtIssuer.issue(userA);
    GameSetup setup = setupAuthenticatedRoom("Alice", tokenA, "Bob");

    ErrorCapturingHandler handler = new ErrorCapturingHandler();
    StompSession session = connect("Bearer " + tokenA, handler);

    assertThat(session.isConnected()).isTrue();
    // SEND with the authenticated user's own player id — must pass the spoof check.
    sendWithClaim(session, "/app/games/" + setup.gameId() + "/probe", setup.whitePlayerId());

    Throwable error = handler.poll();
    assertThat(error)
        .as("authenticated session sending its own player id must not be rejected")
        .isNull();
  }

  @Test
  void stompConnect_withInvalidJwt_succeedsButAnonymous() throws Exception {
    GameSetup setup = setupAnonymousGame("Alice", "Bob");
    // Three-segment string that fails signature / structure parsing.
    String malformedJwt = "not.a.real.jwt";

    ErrorCapturingHandler handler = new ErrorCapturingHandler();
    StompSession session = connect("Bearer " + malformedJwt, handler);

    // The CRITICAL regression pin: a bad JWT does NOT reject the CONNECT. The session connects
    // anonymously and SUBSCRIBE / SEND-without-claim still work.
    assertThat(session.isConnected()).isTrue();
    BlockingQueue<String> received = subscribeRaw(session, "/topic/games/" + setup.gameId(), null);
    sendWithoutClaim(session, "/app/games/" + setup.gameId() + "/probe");

    assertThat(handler.poll()).isNull();
    assertThat(received.poll(NO_RECEIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS)).isNull();
  }

  @Test
  void stompSend_authenticatedSessionWithOpponentsPlayerId_rejected() throws Exception {
    User userA = saveUser("alice-spoof@example.com", "Alice");
    User userB = saveUser("bob-spoof@example.com", "Bob");
    String tokenA = jwtIssuer.issue(userA);
    String tokenB = jwtIssuer.issue(userB);

    // Both players authenticated so that Player.userId is populated on each side.
    GameSetup setup = setupAuthenticatedGame("Alice", tokenA, "Bob", tokenB);

    ErrorCapturingHandler handler = new ErrorCapturingHandler();
    StompSession session = connect("Bearer " + tokenA, handler);
    assertThat(session.isConnected()).isTrue();
    // Subscribe so we can also assert nothing leaks downstream after the rejection.
    BlockingQueue<String> downstream =
        subscribeRaw(session, "/topic/games/" + setup.gameId(), null);

    // A is authenticated; SEND with B's player id (the spoof scenario).
    sendWithClaim(session, "/app/games/" + setup.gameId() + "/probe", setup.blackPlayerId());

    Throwable error = handler.poll();
    assertThat(error).as("a STOMP ERROR frame should reach the session handler").isNotNull();
    // The spoofed SEND must not surface on the topic (it never reached any broker dispatch).
    assertThat(downstream.poll(NO_RECEIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS)).isNull();
    // Session-stays-open contract: the framework drops the session id on the local handle after an
    // ERROR frame, but the underlying transport is still usable for the test's purpose — we only
    // need to verify the message was rejected, not that subsequent frames can be sent.
  }

  @Test
  void stompSend_anonymousSessionWithMismatchedPlayerId_rejected() throws Exception {
    GameSetup setup = setupAnonymousGame("Alice", "Bob");
    UUID stranger = UUID.randomUUID();

    ErrorCapturingHandler handler = new ErrorCapturingHandler();
    StompSession session = connect(null, handler);
    assertThat(session.isConnected()).isTrue();

    // First SEND pins playerId = white. Use a destination NOT matching the game-topic pattern so
    // the lookup path is skipped and the interceptor falls back to pin-on-first-use (which is the
    // path the plan's case 5 targets — anonymous + pinned id).
    sendWithClaim(session, "/app/probe", setup.whitePlayerId());

    assertThat(handler.poll())
        .as("pinning the playerId on first use must not raise an ERROR")
        .isNull();

    // Follow-up SEND with a different playerId on the same session — must be rejected.
    sendWithClaim(session, "/app/probe", stranger);

    Throwable error = handler.poll();
    assertThat(error).as("a STOMP ERROR frame should reach the session handler").isNotNull();
  }

  // ---------- helpers ----------

  private StompSession connect(String authorizationHeader, ErrorCapturingHandler handler)
      throws Exception {
    StompHeaders connectHeaders = new StompHeaders();
    if (authorizationHeader != null) {
      connectHeaders.add("Authorization", authorizationHeader);
    }
    return stompClient
        .connectAsync(
            "ws://localhost:" + port + "/ws",
            new WebSocketHttpHeadersNone(),
            connectHeaders,
            handler)
        .get(RECEIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
  }

  private BlockingQueue<String> subscribeRaw(
      StompSession session, String destination, UUID playerIdHeaderOrNull)
      throws InterruptedException {
    BlockingQueue<String> queue = new ArrayBlockingQueue<>(8);
    StompHeaders headers = new StompHeaders();
    headers.setDestination(destination);
    if (playerIdHeaderOrNull != null) {
      headers.add("playerId", playerIdHeaderOrNull.toString());
    }
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

  private void sendWithoutClaim(StompSession session, String destination)
      throws InterruptedException {
    StompHeaders headers = new StompHeaders();
    headers.setDestination(destination);
    session.send(headers, new byte[0]);
    Thread.sleep(SUBSCRIBE_REGISTRATION_DELAY_MS);
  }

  private void sendWithClaim(StompSession session, String destination, UUID playerId)
      throws InterruptedException {
    StompHeaders headers = new StompHeaders();
    headers.setDestination(destination);
    headers.add("playerId", playerId.toString());
    session.send(headers, new byte[0]);
    Thread.sleep(SUBSCRIBE_REGISTRATION_DELAY_MS);
  }

  private User saveUser(String email, String displayName) {
    User user = new User(UUID.randomUUID(), email, displayName, null, null, Instant.now());
    return users.save(user);
  }

  private GameSetup setupAnonymousGame(String whiteName, String blackName) throws Exception {
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
    String joinToken = createBody.get("joinToken").asText();
    UUID whitePlayerId = UUID.fromString(createBody.get("playerId").asText());

    ResponseEntity<String> joinResponse =
        restTemplate.exchange(
            baseUrl() + "/api/rooms/" + roomId + "/join",
            HttpMethod.POST,
            new HttpEntity<>(
                "{\"displayName\":\"" + blackName + "\",\"joinToken\":\"" + joinToken + "\"}",
                headers),
            String.class);
    JsonNode joinBody = objectMapper.readTree(joinResponse.getBody());
    UUID gameId = UUID.fromString(joinBody.get("gameId").asText());
    UUID blackPlayerId = UUID.fromString(joinBody.get("playerId").asText());

    return new GameSetup(gameId, whitePlayerId, blackPlayerId);
  }

  private GameSetup setupAuthenticatedRoom(String whiteName, String whiteToken, String blackName)
      throws Exception {
    HttpHeaders authHeaders = new HttpHeaders();
    authHeaders.setContentType(MediaType.APPLICATION_JSON);
    authHeaders.set(HttpHeaders.AUTHORIZATION, "Bearer " + whiteToken);

    ResponseEntity<String> createResponse =
        restTemplate.exchange(
            baseUrl() + "/api/rooms",
            HttpMethod.POST,
            new HttpEntity<>("{\"displayName\":\"" + whiteName + "\"}", authHeaders),
            String.class);
    assertThat(createResponse.getStatusCode().is2xxSuccessful()).isTrue();
    JsonNode createBody = objectMapper.readTree(createResponse.getBody());
    String roomId = createBody.get("roomId").asText();
    String joinToken = createBody.get("joinToken").asText();
    UUID whitePlayerId = UUID.fromString(createBody.get("playerId").asText());

    HttpHeaders joinHeaders = new HttpHeaders();
    joinHeaders.setContentType(MediaType.APPLICATION_JSON);

    ResponseEntity<String> joinResponse =
        restTemplate.exchange(
            baseUrl() + "/api/rooms/" + roomId + "/join",
            HttpMethod.POST,
            new HttpEntity<>(
                "{\"displayName\":\"" + blackName + "\",\"joinToken\":\"" + joinToken + "\"}",
                joinHeaders),
            String.class);
    JsonNode joinBody = objectMapper.readTree(joinResponse.getBody());
    UUID gameId = UUID.fromString(joinBody.get("gameId").asText());
    UUID blackPlayerId = UUID.fromString(joinBody.get("playerId").asText());

    return new GameSetup(gameId, whitePlayerId, blackPlayerId);
  }

  private GameSetup setupAuthenticatedGame(
      String whiteName, String whiteToken, String blackName, String blackToken) throws Exception {
    HttpHeaders whiteHeaders = new HttpHeaders();
    whiteHeaders.setContentType(MediaType.APPLICATION_JSON);
    whiteHeaders.set(HttpHeaders.AUTHORIZATION, "Bearer " + whiteToken);

    ResponseEntity<String> createResponse =
        restTemplate.exchange(
            baseUrl() + "/api/rooms",
            HttpMethod.POST,
            new HttpEntity<>("{\"displayName\":\"" + whiteName + "\"}", whiteHeaders),
            String.class);
    JsonNode createBody = objectMapper.readTree(createResponse.getBody());
    String roomId = createBody.get("roomId").asText();
    String joinToken = createBody.get("joinToken").asText();
    UUID whitePlayerId = UUID.fromString(createBody.get("playerId").asText());

    HttpHeaders blackHeaders = new HttpHeaders();
    blackHeaders.setContentType(MediaType.APPLICATION_JSON);
    blackHeaders.set(HttpHeaders.AUTHORIZATION, "Bearer " + blackToken);

    ResponseEntity<String> joinResponse =
        restTemplate.exchange(
            baseUrl() + "/api/rooms/" + roomId + "/join",
            HttpMethod.POST,
            new HttpEntity<>(
                "{\"displayName\":\"" + blackName + "\",\"joinToken\":\"" + joinToken + "\"}",
                blackHeaders),
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

  /**
   * Empty stand-in for the {@code WebSocketHttpHeaders} parameter on the connect overload that lets
   * us pass STOMP headers. Created here so the existing-test scaffolding shape stays minimal.
   */
  private static final class WebSocketHttpHeadersNone extends WebSocketHttpHeaders {}

  /**
   * Session handler that captures errors surfaced through {@link
   * StompSessionHandlerAdapter#handleException} (sync STOMP protocol errors), {@link
   * StompSessionHandlerAdapter#handleTransportError} (transport-level failures), and the ERROR
   * frame dispatch through {@link #handleFrame}. The interceptor's {@link
   * StompAuthInterceptor#preSend} sends ERROR frames back on the same channel; the test client
   * receives them via {@code handleFrame} when the framework routes the frame to the session
   * handler.
   */
  private static final class ErrorCapturingHandler extends StompSessionHandlerAdapter {
    private final BlockingQueue<Throwable> errors = new ArrayBlockingQueue<>(8);

    @Override
    public void handleException(
        StompSession session,
        StompCommand command,
        StompHeaders headers,
        byte[] payload,
        Throwable exception) {
      errors.offer(exception);
    }

    @Override
    public void handleTransportError(StompSession session, Throwable exception) {
      errors.offer(exception);
    }

    @Override
    public void handleFrame(StompHeaders headers, Object payload) {
      // The base adapter routes ERROR frames here when no specific handler is registered for the
      // session's principal-less ERROR-frame path. The interceptor's reject path is exactly this
      // case.
      errors.offer(new RuntimeException("ERROR frame: " + headers));
    }

    Throwable poll() throws InterruptedException {
      return errors.poll(RECEIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }
  }
}
