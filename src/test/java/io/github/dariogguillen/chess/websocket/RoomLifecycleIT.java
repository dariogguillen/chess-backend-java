package io.github.dariogguillen.chess.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.dariogguillen.chess.TestcontainersConfiguration;
import java.lang.reflect.Type;
import java.net.URI;
import java.nio.charset.StandardCharsets;
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
 * STOMP integration coverage for the {@code /topic/rooms/{roomId}} topic added by feature 9.5.
 * Boots the full Spring context on a random port (real WebSocket handshake required), connects via
 * {@link WebSocketStompClient}, and exercises the four behaviours the contract relies on:
 *
 * <ol>
 *   <li>Happy path: a subscriber that connected before the join receives the event.
 *   <li>Late subscriber: a subscriber that connects after the join receives nothing (STOMP no
 *       replay, locked in).
 *   <li>Idempotency: a duplicate join to a full room returns 409 ROOM_FULL and produces no second
 *       broadcast.
 *   <li>Wire-format discriminator: the JSON payload contains {@code "type":"ROOM_JOINED"} so the
 *       discriminator field is part of the serialized contract, not just the Java record.
 * </ol>
 *
 * Mirrors the setup pattern in {@code GameWebSocketIT} (lines 78-91): {@link WebSocketStompClient}
 * + {@link MappingJackson2MessageConverter} + {@link JavaTimeModule}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RoomLifecycleIT {

  /** Timeout for assertions that DO expect to receive an event. */
  private static final long RECEIVE_TIMEOUT_MS = 2_000L;

  /** Timeout for assertions that expect NO event — short, but long enough to catch slow leaks. */
  private static final long NO_RECEIVE_TIMEOUT_MS = 500L;

  /**
   * Brief wait after {@code session.subscribe(...)} returns, before triggering the join. The
   * subscribe-ack round-trip is asynchronous; without this gap the broadcast can fire before the
   * subscription is registered and the test misses the frame.
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
    // JavaTimeModule. The RoomJoinedEvent does not currently carry any java.time types, but
    // registering the module mirrors GameWebSocketIT's setup and keeps the test resilient to
    // future fields (e.g. a joinedAt Instant if the event grows one).
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
  void subscriberBeforeJoin_receivesRoomJoinedEvent() throws Exception {
    RoomHandle room = createRoom("Alice");
    StompSession session = connect();
    BlockingQueue<RoomJoinedEvent> queue = subscribeForEvent(session, room.roomId());

    JoinResult joined = joinRoom(room, "Bob");

    RoomJoinedEvent received = queue.poll(RECEIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertThat(received).isNotNull();
    assertThat(received.type()).isEqualTo("ROOM_JOINED");
    assertThat(received.roomId()).isEqualTo(room.roomId());
    assertThat(received.gameId()).isEqualTo(joined.gameId());
    assertThat(received.blackPlayer()).isNotNull();
    assertThat(received.blackPlayer().id()).isEqualTo(joined.joinerId());
    assertThat(received.blackPlayer().displayName()).isEqualTo("Bob");
  }

  @Test
  void subscriberAfterJoin_doesNotReceiveEvent() throws Exception {
    RoomHandle room = createRoom("Alice");
    joinRoom(room, "Bob");
    StompSession session = connect();
    BlockingQueue<RoomJoinedEvent> queue = subscribeForEvent(session, room.roomId());

    RoomJoinedEvent leak = queue.poll(NO_RECEIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertThat(leak).isNull();
  }

  @Test
  void duplicateJoinOnFullRoom_returns409AndDoesNotBroadcastSecondEvent() throws Exception {
    RoomHandle room = createRoom("Alice");
    StompSession session = connect();
    BlockingQueue<RoomJoinedEvent> queue = subscribeForEvent(session, room.roomId());

    joinRoom(room, "Bob");
    // Drain the legitimate event so the next poll only sees the (absent) second broadcast.
    RoomJoinedEvent first = queue.poll(RECEIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertThat(first).isNotNull();

    ResponseEntity<String> rejected = joinRoomExpectingFailure(room, "Carol");
    assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    JsonNode errorBody = objectMapper.readTree(rejected.getBody());
    assertThat(errorBody.get("error").asText()).isEqualTo("ROOM_FULL");

    RoomJoinedEvent second = queue.poll(NO_RECEIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertThat(second).isNull();
  }

  @Test
  void wireFormat_containsTypeDiscriminatorField() throws Exception {
    RoomHandle room = createRoom("Alice");
    StompSession session = connect();
    BlockingQueue<String> rawQueue = subscribeForRawJson(session, room.roomId());

    joinRoom(room, "Bob");

    String rawJson = rawQueue.poll(RECEIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertThat(rawJson).isNotNull();
    JsonNode tree = objectMapper.readTree(rawJson);
    assertThat(tree.get("type")).isNotNull();
    assertThat(tree.get("type").asText()).isEqualTo("ROOM_JOINED");
    assertThat(tree.get("roomId").asText()).isEqualTo(room.roomId());
    assertThat(tree.get("gameId").asText()).isNotBlank();
    assertThat(tree.get("blackPlayer")).isNotNull();
    assertThat(tree.get("blackPlayer").get("displayName").asText()).isEqualTo("Bob");
  }

  private StompSession connect() throws Exception {
    return stompClient
        .connectAsync("ws://localhost:" + port + "/ws", new StompSessionHandlerAdapter() {})
        .get(RECEIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
  }

  private BlockingQueue<RoomJoinedEvent> subscribeForEvent(StompSession session, String roomId)
      throws InterruptedException {
    BlockingQueue<RoomJoinedEvent> queue = new ArrayBlockingQueue<>(8);
    session.subscribe(
        "/topic/rooms/" + roomId,
        new StompFrameHandler() {
          @Override
          public Type getPayloadType(StompHeaders headers) {
            return RoomJoinedEvent.class;
          }

          @Override
          public void handleFrame(StompHeaders headers, Object payload) {
            queue.offer((RoomJoinedEvent) payload);
          }
        });
    Thread.sleep(SUBSCRIBE_REGISTRATION_DELAY_MS);
    return queue;
  }

  /**
   * Subscribes asking for the raw payload as {@code byte[]} (the lowest-level shape STOMP exposes),
   * decodes it as UTF-8 JSON, and offers the string to the queue. Used by the wire-format test to
   * assert the discriminator field is present in the serialized output, not just in the Java
   * record.
   */
  private BlockingQueue<String> subscribeForRawJson(StompSession session, String roomId)
      throws InterruptedException {
    BlockingQueue<String> queue = new ArrayBlockingQueue<>(8);
    session.subscribe(
        "/topic/rooms/" + roomId,
        new StompFrameHandler() {
          @Override
          public Type getPayloadType(StompHeaders headers) {
            return byte[].class;
          }

          @Override
          public void handleFrame(StompHeaders headers, Object payload) {
            queue.offer(new String((byte[]) payload, StandardCharsets.UTF_8));
          }
        });
    Thread.sleep(SUBSCRIBE_REGISTRATION_DELAY_MS);
    return queue;
  }

  private RoomHandle createRoom(String displayName) throws Exception {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    ResponseEntity<String> response =
        restTemplate.exchange(
            baseUrl() + "/api/rooms",
            HttpMethod.POST,
            new HttpEntity<>("{\"displayName\":\"" + displayName + "\"}", headers),
            String.class);
    JsonNode body = objectMapper.readTree(response.getBody());
    return new RoomHandle(body.get("roomId").asText(), body.get("joinToken").asText());
  }

  private JoinResult joinRoom(RoomHandle room, String displayName) throws Exception {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    ResponseEntity<String> response =
        restTemplate.exchange(
            baseUrl() + "/api/rooms/" + room.roomId() + "/join",
            HttpMethod.POST,
            new HttpEntity<>(
                "{\"displayName\":\""
                    + displayName
                    + "\",\"joinToken\":\""
                    + room.joinToken()
                    + "\"}",
                headers),
            String.class);
    JsonNode body = objectMapper.readTree(response.getBody());
    return new JoinResult(
        UUID.fromString(body.get("gameId").asText()),
        UUID.fromString(body.get("playerId").asText()));
  }

  /**
   * Like {@link #joinRoom(RoomHandle, String)}, but tolerates 4xx by reconstructing a {@link
   * ResponseEntity} from the thrown {@link HttpStatusCodeException}. Used by the
   * full-room-rejection test which expects 409 — it supplies the correct token so the rejection is
   * the room-full conflict, not a token failure.
   */
  private ResponseEntity<String> joinRoomExpectingFailure(RoomHandle room, String displayName) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    try {
      return restTemplate.exchange(
          baseUrl() + "/api/rooms/" + room.roomId() + "/join",
          HttpMethod.POST,
          new HttpEntity<>(
              "{\"displayName\":\""
                  + displayName
                  + "\",\"joinToken\":\""
                  + room.joinToken()
                  + "\"}",
              headers),
          String.class);
    } catch (HttpStatusCodeException ex) {
      HttpStatusCode status = ex.getStatusCode();
      return ResponseEntity.status(status).body(ex.getResponseBodyAsString());
    }
  }

  private String baseUrl() {
    return URI.create("http://localhost:" + port).toString();
  }

  private record JoinResult(UUID gameId, UUID joinerId) {}

  /** A created room's short code plus the secret join token needed to join it (feature 22.7). */
  private record RoomHandle(String roomId, String joinToken) {}
}
