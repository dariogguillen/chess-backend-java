package io.github.dariogguillen.chess.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.dariogguillen.chess.TestcontainersConfiguration;
import java.lang.reflect.Type;
import java.net.URI;
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
import org.springframework.web.client.RestTemplate;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * Integration tests for the spectator-mode viewer-count tracker added in feature 6.5. Boots the
 * full Spring context on a random port, opens real STOMP sessions against {@code /ws}, and asserts
 * that {@link ViewerCountEvent} broadcasts to {@code /topic/games/{gameId}/viewers} match the
 * expected count as subscribers join, leave, or disconnect. The {@code playerId} STOMP header is
 * exercised: players are excluded from the count, non-players are counted.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ViewerCountIT {

  /** Timeout for assertions that DO expect to receive a ViewerCountEvent. */
  private static final long RECEIVE_TIMEOUT_MS = 2_000L;

  /** Timeout for assertions that expect NO ViewerCountEvent. Short — keeps negative tests cheap. */
  private static final long NO_RECEIVE_TIMEOUT_MS = 500L;

  /**
   * Brief wait after {@code session.subscribe(...)} returns, before triggering the next server-side
   * action. Same magnitude as in {@link GameWebSocketIT}: the subscribe-ack round-trip is async,
   * and without this gap the server-side broadcast can fire before the subscription is registered.
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
    // ViewerCountEvent carries no Instant today, but match the GameWebSocketIT pattern so that
    // adding a timestamp field later is a one-line wire change rather than a debugging session.
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
  void nonPlayerSubscribes_countTicksToOne() throws Exception {
    GameSetup setup = setupGame("Alice", "Bob");
    StompSession session = connect();
    BlockingQueue<ViewerCountEvent> viewers = subscribeViewers(session, setup.gameId());
    subscribeGame(session, setup.gameId(), null);

    ViewerCountEvent event = viewers.poll(RECEIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertThat(event).isNotNull();
    assertThat(event.gameId()).isEqualTo(setup.gameId());
    assertThat(event.count()).isEqualTo(1);
  }

  @Test
  void playerSubscribes_countStaysAtZero_thenNonPlayerJoinsAndCountIsOne() throws Exception {
    GameSetup setup = setupGame("Alice", "Bob");

    StompSession sessionA = connect();
    BlockingQueue<ViewerCountEvent> viewersA = subscribeViewers(sessionA, setup.gameId());
    subscribeGame(sessionA, setup.gameId(), setup.whitePlayerId());

    // Player subscribed; no broadcast expected because the player is excluded from the count.
    ViewerCountEvent leak = viewersA.poll(NO_RECEIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertThat(leak).isNull();

    StompSession sessionB = connect();
    BlockingQueue<ViewerCountEvent> viewersB = subscribeViewers(sessionB, setup.gameId());
    subscribeGame(sessionB, setup.gameId(), null);

    ViewerCountEvent receivedB = viewersB.poll(RECEIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertThat(receivedB).isNotNull();
    assertThat(receivedB.count()).isEqualTo(1);

    ViewerCountEvent receivedA = viewersA.poll(RECEIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertThat(receivedA).isNotNull();
    assertThat(receivedA.count()).isEqualTo(1);
  }

  @Test
  void twoNonPlayerSubscribers_countTicksToTwo() throws Exception {
    GameSetup setup = setupGame("Alice", "Bob");

    StompSession sessionA = connect();
    BlockingQueue<ViewerCountEvent> viewersA = subscribeViewers(sessionA, setup.gameId());
    subscribeGame(sessionA, setup.gameId(), null);

    ViewerCountEvent first = viewersA.poll(RECEIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertThat(first).isNotNull();
    assertThat(first.count()).isEqualTo(1);

    StompSession sessionB = connect();
    BlockingQueue<ViewerCountEvent> viewersB = subscribeViewers(sessionB, setup.gameId());
    subscribeGame(sessionB, setup.gameId(), null);

    ViewerCountEvent secondOnB = viewersB.poll(RECEIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertThat(secondOnB).isNotNull();
    assertThat(secondOnB.count()).isEqualTo(2);

    ViewerCountEvent secondOnA = viewersA.poll(RECEIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertThat(secondOnA).isNotNull();
    assertThat(secondOnA.count()).isEqualTo(2);
  }

  @Test
  void subscriberDisconnects_countTicksDown() throws Exception {
    GameSetup setup = setupGame("Alice", "Bob");

    StompSession sessionA = connect();
    BlockingQueue<ViewerCountEvent> viewersA = subscribeViewers(sessionA, setup.gameId());
    subscribeGame(sessionA, setup.gameId(), null);
    assertThat(viewersA.poll(RECEIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS)).isNotNull();

    StompSession sessionB = connect();
    BlockingQueue<ViewerCountEvent> viewersB = subscribeViewers(sessionB, setup.gameId());
    subscribeGame(sessionB, setup.gameId(), null);
    ViewerCountEvent two = viewersB.poll(RECEIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertThat(two).isNotNull();
    assertThat(two.count()).isEqualTo(2);
    // Drain the "count: 2" event from A's queue so the next assertion reads the post-disconnect
    // event.
    assertThat(viewersA.poll(RECEIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS)).isNotNull();

    sessionB.disconnect();

    ViewerCountEvent down = viewersA.poll(RECEIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertThat(down).isNotNull();
    assertThat(down.count()).isEqualTo(1);
  }

  @Test
  void subscriberUnsubscribes_countTicksDown() throws Exception {
    GameSetup setup = setupGame("Alice", "Bob");

    StompSession sessionA = connect();
    BlockingQueue<ViewerCountEvent> viewersA = subscribeViewers(sessionA, setup.gameId());
    subscribeGame(sessionA, setup.gameId(), null);
    assertThat(viewersA.poll(RECEIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS)).isNotNull();

    StompSession sessionB = connect();
    BlockingQueue<ViewerCountEvent> viewersB = subscribeViewers(sessionB, setup.gameId());
    StompSession.Subscription gameSubB = subscribeGame(sessionB, setup.gameId(), null);
    ViewerCountEvent two = viewersB.poll(RECEIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertThat(two).isNotNull();
    assertThat(two.count()).isEqualTo(2);
    // Drain the "count: 2" event from A's queue.
    assertThat(viewersA.poll(RECEIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS)).isNotNull();

    // B unsubscribes only from the game topic, stays connected (and still subscribed to viewers).
    gameSubB.unsubscribe();

    ViewerCountEvent downA = viewersA.poll(RECEIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertThat(downA).isNotNull();
    assertThat(downA.count()).isEqualTo(1);

    ViewerCountEvent downB = viewersB.poll(RECEIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertThat(downB).isNotNull();
    assertThat(downB.count()).isEqualTo(1);
  }

  private StompSession connect() throws Exception {
    return stompClient
        .connectAsync("ws://localhost:" + port + "/ws", new StompSessionHandlerAdapter() {})
        .get(RECEIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
  }

  /**
   * Subscribes the session to {@code /topic/games/{gameId}/viewers} so that {@link
   * ViewerCountEvent} broadcasts land in the returned queue. Does NOT itself bump the count — the
   * tracker's regex is anchored at {@code $} so the {@code /viewers} sub-topic does not match.
   */
  private BlockingQueue<ViewerCountEvent> subscribeViewers(StompSession session, String gameId)
      throws InterruptedException {
    BlockingQueue<ViewerCountEvent> queue = new ArrayBlockingQueue<>(8);
    session.subscribe(
        "/topic/games/" + gameId + "/viewers",
        new StompFrameHandler() {
          @Override
          public Type getPayloadType(StompHeaders headers) {
            return ViewerCountEvent.class;
          }

          @Override
          public void handleFrame(StompHeaders headers, Object payload) {
            queue.offer((ViewerCountEvent) payload);
          }
        });
    Thread.sleep(SUBSCRIBE_REGISTRATION_DELAY_MS);
    return queue;
  }

  /**
   * Subscribes to {@code /topic/games/{gameId}} — the trigger for the viewer-count tracker. If
   * {@code playerId} is non-null, sends it as a native STOMP header on the SUBSCRIBE frame; the
   * server uses it to exclude the subscriber from the count if it matches white or black of the
   * game. Discards inbound payloads (we only care about the side effect on the viewers topic).
   */
  private StompSession.Subscription subscribeGame(
      StompSession session, String gameId, String playerId) throws InterruptedException {
    StompHeaders headers = new StompHeaders();
    headers.setDestination("/topic/games/" + gameId);
    if (playerId != null) {
      headers.add("playerId", playerId);
    }
    StompSession.Subscription sub =
        session.subscribe(
            headers,
            new StompFrameHandler() {
              @Override
              public Type getPayloadType(StompHeaders frameHeaders) {
                return byte[].class;
              }

              @Override
              public void handleFrame(StompHeaders frameHeaders, Object payload) {
                // Intentionally ignored.
              }
            });
    Thread.sleep(SUBSCRIBE_REGISTRATION_DELAY_MS);
    return sub;
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
    String whitePlayerId = createBody.get("playerId").asText();

    ResponseEntity<String> joinResponse =
        restTemplate.exchange(
            baseUrl() + "/api/rooms/" + roomId + "/join",
            HttpMethod.POST,
            new HttpEntity<>("{\"displayName\":\"" + blackName + "\"}", headers),
            String.class);
    JsonNode joinBody = objectMapper.readTree(joinResponse.getBody());
    String gameId = joinBody.get("gameId").asText();
    String blackPlayerId = joinBody.get("playerId").asText();

    return new GameSetup(gameId, whitePlayerId, blackPlayerId);
  }

  private String baseUrl() {
    return URI.create("http://localhost:" + port).toString();
  }

  private record GameSetup(String gameId, String whitePlayerId, String blackPlayerId) {}
}
