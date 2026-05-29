package io.github.dariogguillen.chess.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.dariogguillen.chess.TestcontainersConfiguration;
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
 * Integration tests for the room-keyed spectator viewer-count tracker (feature 22.5, {@code
 * spectators-in-room}; originally feature 6.5, then re-keyed from game to room). Boots the full
 * Spring context on a random port, opens real STOMP sessions against {@code /ws}, and asserts that
 * {@link ViewerCountEvent} broadcasts to {@code /topic/rooms/{roomId}/viewers} match the expected
 * count as subscribers join, leave, or disconnect. The {@code playerId} STOMP header is exercised:
 * players of the room are excluded from the count, non-players are counted. The headline case is a
 * spectator counted while the room is still {@code WAITING_FOR_PLAYER} (before any game exists),
 * and the count staying stable across the opponent's join.
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
    RoomSetup setup = setupGame("Alice", "Bob");
    StompSession session = connect();
    BlockingQueue<ViewerCountEvent> viewers = subscribeViewers(session, setup.roomId());
    subscribeRoom(session, setup.roomId(), null);

    ViewerCountEvent event = viewers.poll(RECEIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertThat(event).isNotNull();
    assertThat(event.roomId()).isEqualTo(setup.roomId());
    assertThat(event.count()).isEqualTo(1);
  }

  @Test
  void spectatorSubscribesWhileWaitingForPlayer_countTicksToOne() throws Exception {
    // Headline case: only POST /api/rooms has happened — no opponent, no game yet. The spectator
    // subscribes to the room topic and is counted from the lobby.
    RoomSetup setup = setupRoom("Alice");
    StompSession session = connect();
    BlockingQueue<ViewerCountEvent> viewers = subscribeViewers(session, setup.roomId());
    subscribeRoom(session, setup.roomId(), null);

    ViewerCountEvent event = viewers.poll(RECEIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertThat(event).isNotNull();
    assertThat(event.roomId()).isEqualTo(setup.roomId());
    assertThat(event.count()).isEqualTo(1);
  }

  @Test
  void spectatorCountStableAcrossOpponentJoin() throws Exception {
    // Spectator is counted while the room waits; a second player then joins (the room goes ACTIVE
    // and the game is created). Because the count is room-keyed, it stays at 1 — the join neither
    // resets it to 0 nor double-counts to 2.
    RoomSetup setup = setupRoom("Alice");
    StompSession session = connect();
    BlockingQueue<ViewerCountEvent> viewers = subscribeViewers(session, setup.roomId());
    subscribeRoom(session, setup.roomId(), null);

    ViewerCountEvent first = viewers.poll(RECEIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertThat(first).isNotNull();
    assertThat(first.count()).isEqualTo(1);

    // Opponent joins via REST — creates the game, flips the room to ACTIVE.
    joinRoom(setup.roomId(), "Bob");

    // No further ViewerCountEvent should be emitted by the join: the room's spectator set is
    // unchanged. The count remains 1 (proven by the absence of any reset-to-0 or bump-to-2 event).
    ViewerCountEvent afterJoin = viewers.poll(NO_RECEIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertThat(afterJoin).isNull();
  }

  @Test
  void roomCreatorSubscribesWithPlayerId_notCounted_thenSpectatorIsOne() throws Exception {
    RoomSetup setup = setupRoom("Alice");

    StompSession creator = connect();
    BlockingQueue<ViewerCountEvent> creatorViewers = subscribeViewers(creator, setup.roomId());
    subscribeRoom(creator, setup.roomId(), setup.creatorPlayerId());

    // Creator declared its playerId — excluded from the count, so no broadcast on its subscribe.
    ViewerCountEvent leak = creatorViewers.poll(NO_RECEIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertThat(leak).isNull();

    StompSession spectator = connect();
    BlockingQueue<ViewerCountEvent> spectatorViewers = subscribeViewers(spectator, setup.roomId());
    subscribeRoom(spectator, setup.roomId(), null);

    ViewerCountEvent onSpectator = spectatorViewers.poll(RECEIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertThat(onSpectator).isNotNull();
    assertThat(onSpectator.count()).isEqualTo(1);

    ViewerCountEvent onCreator = creatorViewers.poll(RECEIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertThat(onCreator).isNotNull();
    assertThat(onCreator.count()).isEqualTo(1);
  }

  @Test
  void twoNonPlayerSubscribers_countTicksToTwo() throws Exception {
    RoomSetup setup = setupGame("Alice", "Bob");

    StompSession sessionA = connect();
    BlockingQueue<ViewerCountEvent> viewersA = subscribeViewers(sessionA, setup.roomId());
    subscribeRoom(sessionA, setup.roomId(), null);

    ViewerCountEvent first = viewersA.poll(RECEIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertThat(first).isNotNull();
    assertThat(first.count()).isEqualTo(1);

    StompSession sessionB = connect();
    BlockingQueue<ViewerCountEvent> viewersB = subscribeViewers(sessionB, setup.roomId());
    subscribeRoom(sessionB, setup.roomId(), null);

    ViewerCountEvent secondOnB = viewersB.poll(RECEIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertThat(secondOnB).isNotNull();
    assertThat(secondOnB.count()).isEqualTo(2);

    ViewerCountEvent secondOnA = viewersA.poll(RECEIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertThat(secondOnA).isNotNull();
    assertThat(secondOnA.count()).isEqualTo(2);
  }

  @Test
  void subscriberDisconnects_countTicksDown() throws Exception {
    RoomSetup setup = setupGame("Alice", "Bob");

    StompSession sessionA = connect();
    BlockingQueue<ViewerCountEvent> viewersA = subscribeViewers(sessionA, setup.roomId());
    subscribeRoom(sessionA, setup.roomId(), null);
    assertThat(viewersA.poll(RECEIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS)).isNotNull();

    StompSession sessionB = connect();
    BlockingQueue<ViewerCountEvent> viewersB = subscribeViewers(sessionB, setup.roomId());
    subscribeRoom(sessionB, setup.roomId(), null);
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
    RoomSetup setup = setupGame("Alice", "Bob");

    StompSession sessionA = connect();
    BlockingQueue<ViewerCountEvent> viewersA = subscribeViewers(sessionA, setup.roomId());
    subscribeRoom(sessionA, setup.roomId(), null);
    assertThat(viewersA.poll(RECEIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS)).isNotNull();

    StompSession sessionB = connect();
    BlockingQueue<ViewerCountEvent> viewersB = subscribeViewers(sessionB, setup.roomId());
    StompSession.Subscription roomSubB = subscribeRoom(sessionB, setup.roomId(), null);
    ViewerCountEvent two = viewersB.poll(RECEIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertThat(two).isNotNull();
    assertThat(two.count()).isEqualTo(2);
    // Drain the "count: 2" event from A's queue.
    assertThat(viewersA.poll(RECEIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS)).isNotNull();

    // B unsubscribes only from the room topic, stays connected (and still subscribed to viewers).
    roomSubB.unsubscribe();

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
   * Subscribes the session to {@code /topic/rooms/{roomId}/viewers} so that {@link
   * ViewerCountEvent} broadcasts land in the returned queue. Does NOT itself bump the count — the
   * tracker's regex is anchored at {@code $} so the {@code /viewers} sub-topic does not match.
   */
  private BlockingQueue<ViewerCountEvent> subscribeViewers(StompSession session, String roomId)
      throws InterruptedException {
    BlockingQueue<ViewerCountEvent> queue = new ArrayBlockingQueue<>(8);
    session.subscribe(
        "/topic/rooms/" + roomId + "/viewers",
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
   * Subscribes to {@code /topic/rooms/{roomId}} — the trigger for the viewer-count tracker. If
   * {@code playerId} is non-null, sends it as a native STOMP header on the SUBSCRIBE frame; the
   * server uses it to exclude the subscriber from the count if it matches a player of the room.
   * Discards inbound payloads (we only care about the side effect on the viewers topic).
   */
  private StompSession.Subscription subscribeRoom(
      StompSession session, String roomId, UUID playerId) throws InterruptedException {
    StompHeaders headers = new StompHeaders();
    headers.setDestination("/topic/rooms/" + roomId);
    if (playerId != null) {
      headers.add("playerId", playerId.toString());
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

  /** Creates a room (one player, WAITING_FOR_PLAYER) and returns its short code + creator id. */
  private RoomSetup setupRoom(String creatorName) throws Exception {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);

    ResponseEntity<String> createResponse =
        restTemplate.exchange(
            baseUrl() + "/api/rooms",
            HttpMethod.POST,
            new HttpEntity<>("{\"displayName\":\"" + creatorName + "\"}", headers),
            String.class);
    JsonNode createBody = objectMapper.readTree(createResponse.getBody());
    String roomId = createBody.get("roomId").asText();
    UUID creatorPlayerId = UUID.fromString(createBody.get("playerId").asText());
    return new RoomSetup(roomId, creatorPlayerId);
  }

  /** Joins an existing room as the second player; flips it to ACTIVE and creates the game. */
  private void joinRoom(String roomId, String joinerName) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    restTemplate.exchange(
        baseUrl() + "/api/rooms/" + roomId + "/join",
        HttpMethod.POST,
        new HttpEntity<>("{\"displayName\":\"" + joinerName + "\"}", headers),
        String.class);
  }

  /** Creates a room and joins it (room ACTIVE, game created). Returns the room short code. */
  private RoomSetup setupGame(String creatorName, String joinerName) throws Exception {
    RoomSetup setup = setupRoom(creatorName);
    joinRoom(setup.roomId(), joinerName);
    return setup;
  }

  private String baseUrl() {
    return URI.create("http://localhost:" + port).toString();
  }

  private record RoomSetup(String roomId, UUID creatorPlayerId) {}
}
