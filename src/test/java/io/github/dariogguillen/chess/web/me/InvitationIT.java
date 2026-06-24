package io.github.dariogguillen.chess.web.me;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.dariogguillen.chess.TestcontainersConfiguration;
import io.github.dariogguillen.chess.persistence.FriendshipRepository;
import io.github.dariogguillen.chess.persistence.UserRepository;
import io.github.dariogguillen.chess.websocket.InvitationReceivedEvent;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * End-to-end integration tests for direct invitations (feature 23.9). Boots the full Spring context
 * on a random port (the per-user STOMP push needs a real WebSocket handshake against a real
 * connector, which {@code MockMvc} alone cannot exercise) with Testcontainers Postgres + Redis, so
 * the JWT filter chain, the friendship gate, the ephemeral Redis store, the room join, and the
 * private-queue push all participate.
 *
 * <p>{@link #cleanState()} wipes friendships before users (FK order) and flushes Redis so each test
 * starts with no stale rooms or invitations.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class InvitationIT {

  private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(5);
  private static final long NO_RECEIVE_TIMEOUT_MS = 1_500L;

  @LocalServerPort private int port;

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserRepository users;
  @Autowired private FriendshipRepository friendships;
  @Autowired private RedisConnectionFactory redisConnectionFactory;

  private WebSocketStompClient stompClient;

  @BeforeEach
  void cleanState() {
    friendships.deleteAll();
    users.deleteAll();
    redisConnectionFactory.getConnection().serverCommands().flushDb();

    stompClient = new WebSocketStompClient(new StandardWebSocketClient());
    MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
    converter.setObjectMapper(new ObjectMapper().registerModule(new JavaTimeModule()));
    stompClient.setMessageConverter(converter);
  }

  @AfterEach
  void tearDown() {
    if (stompClient != null) {
      stompClient.stop();
    }
  }

  // --- send --------------------------------------------------------------------------------

  @Test
  void send_happyPath_returns201AndAppearsInInviteeList() throws Exception {
    Registered alice = register("alice@example.com", "Alice");
    Registered bob = register("bob@example.com", "Bob");
    befriend(alice, bob);
    String roomId = createRoom(alice);

    sendInvitation(alice, roomId, bob.id()).andExpect(status().isCreated());

    mockMvc
        .perform(get("/api/me/invitations").header(HttpHeaders.AUTHORIZATION, bearer(bob)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(1)))
        .andExpect(jsonPath("$[0].roomId", equalTo(roomId)))
        .andExpect(jsonPath("$[0].inviterUserId", equalTo(alice.id().toString())))
        .andExpect(jsonPath("$[0].inviterDisplayName", equalTo("Alice")))
        .andExpect(jsonPath("$[0].side", equalTo("BLACK")))
        .andExpect(jsonPath("$[0].createdAt").exists());
  }

  @Test
  void send_reSendSamePair_isIdempotent() throws Exception {
    Registered alice = register("alice@example.com", "Alice");
    Registered bob = register("bob@example.com", "Bob");
    befriend(alice, bob);
    String roomId = createRoom(alice);

    sendInvitation(alice, roomId, bob.id()).andExpect(status().isCreated());
    sendInvitation(alice, roomId, bob.id()).andExpect(status().isCreated());

    // Still exactly one invitation for the pair — the second send overwrote rather than duplicated.
    mockMvc
        .perform(get("/api/me/invitations").header(HttpHeaders.AUTHORIZATION, bearer(bob)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(1)));
  }

  @Test
  void send_nonFriend_returns404FriendNotFound() throws Exception {
    Registered alice = register("alice@example.com", "Alice");
    Registered bob = register("bob@example.com", "Bob");
    // No befriend step.
    String roomId = createRoom(alice);

    sendInvitation(alice, roomId, bob.id())
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error", equalTo("FRIEND_NOT_FOUND")));
  }

  @Test
  void send_unknownRoom_returns404RoomNotFound() throws Exception {
    Registered alice = register("alice@example.com", "Alice");
    Registered bob = register("bob@example.com", "Bob");
    befriend(alice, bob);

    sendInvitation(alice, "ZZZZZZ", bob.id())
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error", equalTo("ROOM_NOT_FOUND")));
  }

  @Test
  void send_callerNotRoomMember_returns403NotRoomMember() throws Exception {
    Registered alice = register("alice@example.com", "Alice");
    Registered bob = register("bob@example.com", "Bob");
    Registered carol = register("carol@example.com", "Carol");
    befriend(carol, bob);
    // Alice created the room; Carol (a member of nothing) tries to invite Bob into Alice's room.
    String aliceRoom = createRoom(alice);

    sendInvitation(carol, aliceRoom, bob.id())
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error", equalTo("NOT_ROOM_MEMBER")));
  }

  @Test
  void send_fullRoom_returns409RoomFull() throws Exception {
    Registered alice = register("alice@example.com", "Alice");
    Registered bob = register("bob@example.com", "Bob");
    Registered carol = register("carol@example.com", "Carol");
    befriend(alice, carol);
    // Alice creates a room and Bob joins it → ACTIVE, no free slot, not joinable.
    CreatedRoom room = createRoomWithToken(alice);
    fillRoom(room.roomId(), room.joinToken(), bob);

    sendInvitation(alice, room.roomId(), carol.id())
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error", equalTo("ROOM_FULL")));
  }

  // --- list pruning ------------------------------------------------------------------------

  @Test
  void list_prunesInvitationsForRoomsThatFilled() throws Exception {
    Registered alice = register("alice@example.com", "Alice");
    Registered bob = register("bob@example.com", "Bob");
    Registered carol = register("carol@example.com", "Carol");
    befriend(alice, bob);
    befriend(alice, carol);
    String roomId = createRoom(alice);

    // Alice invites both Bob and Carol to the same room.
    sendInvitation(alice, roomId, bob.id()).andExpect(status().isCreated());
    sendInvitation(alice, roomId, carol.id()).andExpect(status().isCreated());

    // Bob accepts → the room fills.
    accept(bob, roomId).andExpect(status().isOk());

    // Carol's still-stored invitation now points at a full room and must be pruned out of her list.
    mockMvc
        .perform(get("/api/me/invitations").header(HttpHeaders.AUTHORIZATION, bearer(carol)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(0)));
  }

  // --- accept ------------------------------------------------------------------------------

  @Test
  void accept_performsTheJoinAndReturnsTheRoom() throws Exception {
    Registered alice = register("alice@example.com", "Alice");
    Registered bob = register("bob@example.com", "Bob");
    befriend(alice, bob);
    String roomId = createRoom(alice);
    sendInvitation(alice, roomId, bob.id()).andExpect(status().isCreated());

    MvcResult result =
        accept(bob, roomId)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.roomId", equalTo(roomId)))
            .andExpect(jsonPath("$.role", equalTo("BLACK")))
            .andExpect(jsonPath("$.gameId").exists())
            .andReturn();
    assertThat(json(result).get("gameId").isNull()).isFalse();

    // The invitation is gone after accept.
    mockMvc
        .perform(get("/api/me/invitations").header(HttpHeaders.AUTHORIZATION, bearer(bob)))
        .andExpect(jsonPath("$", hasSize(0)));

    // The room is now ACTIVE with two players.
    mockMvc
        .perform(get("/api/rooms/{id}", roomId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status", equalTo("ACTIVE")))
        .andExpect(jsonPath("$.players", hasSize(2)));
  }

  @Test
  void accept_inviterReceivesRoomJoinedEventOnTopic() throws Exception {
    Registered alice = register("alice@example.com", "Alice");
    Registered bob = register("bob@example.com", "Bob");
    befriend(alice, bob);
    String roomId = createRoom(alice);
    sendInvitation(alice, roomId, bob.id()).andExpect(status().isCreated());

    // Alice (the inviter) subscribes to the room topic, exactly as she would after POST /api/rooms.
    StompSession aliceSession = connect("Bearer " + alice.token());
    BlockingQueue<String> roomEvents = subscribe(aliceSession, "/topic/rooms/" + roomId);

    accept(bob, roomId).andExpect(status().isOk());

    String received = roomEvents.poll(AWAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    assertThat(received).as("inviter should receive RoomJoinedEvent on accept").isNotNull();
    JsonNode event = objectMapper.readTree(received);
    assertThat(event.get("type").asText()).isEqualTo("ROOM_JOINED");
    assertThat(event.get("roomId").asText()).isEqualTo(roomId);
  }

  @Test
  void accept_afterRoomFilled_returns409RoomFull() throws Exception {
    Registered alice = register("alice@example.com", "Alice");
    Registered bob = register("bob@example.com", "Bob");
    Registered carol = register("carol@example.com", "Carol");
    befriend(alice, bob);
    befriend(alice, carol);
    String roomId = createRoom(alice);
    sendInvitation(alice, roomId, bob.id()).andExpect(status().isCreated());
    sendInvitation(alice, roomId, carol.id()).andExpect(status().isCreated());

    // Bob accepts first, filling the room.
    accept(bob, roomId).andExpect(status().isOk());

    // Carol's invitation is still stored, but the room is full. Find→isJoinable(false) → 404 prune.
    accept(carol, roomId)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error", equalTo("INVITATION_NOT_FOUND")));
  }

  @Test
  void accept_noInvitation_returns404() throws Exception {
    Registered alice = register("alice@example.com", "Alice");
    Registered bob = register("bob@example.com", "Bob");
    befriend(alice, bob);
    String roomId = createRoom(alice);
    // No invitation sent to Bob.

    accept(bob, roomId)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error", equalTo("INVITATION_NOT_FOUND")));
  }

  // --- decline -----------------------------------------------------------------------------

  @Test
  void decline_removesTheInvitation() throws Exception {
    Registered alice = register("alice@example.com", "Alice");
    Registered bob = register("bob@example.com", "Bob");
    befriend(alice, bob);
    String roomId = createRoom(alice);
    sendInvitation(alice, roomId, bob.id()).andExpect(status().isCreated());

    mockMvc
        .perform(
            delete("/api/me/invitations/{roomId}", roomId)
                .header(HttpHeaders.AUTHORIZATION, bearer(bob)))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(get("/api/me/invitations").header(HttpHeaders.AUTHORIZATION, bearer(bob)))
        .andExpect(jsonPath("$", hasSize(0)));
  }

  @Test
  void decline_noInvitation_returns404() throws Exception {
    Registered alice = register("alice@example.com", "Alice");
    Registered bob = register("bob@example.com", "Bob");
    befriend(alice, bob);
    String roomId = createRoom(alice);

    mockMvc
        .perform(
            delete("/api/me/invitations/{roomId}", roomId)
                .header(HttpHeaders.AUTHORIZATION, bearer(bob)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error", equalTo("INVITATION_NOT_FOUND")));
  }

  // --- cancel ------------------------------------------------------------------------------

  @Test
  void cancel_byInviter_removesTheInvitation() throws Exception {
    Registered alice = register("alice@example.com", "Alice");
    Registered bob = register("bob@example.com", "Bob");
    befriend(alice, bob);
    String roomId = createRoom(alice);
    sendInvitation(alice, roomId, bob.id()).andExpect(status().isCreated());

    mockMvc
        .perform(
            delete("/api/me/invitations/{roomId}/to/{inviteeUserId}", roomId, bob.id())
                .header(HttpHeaders.AUTHORIZATION, bearer(alice)))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(get("/api/me/invitations").header(HttpHeaders.AUTHORIZATION, bearer(bob)))
        .andExpect(jsonPath("$", hasSize(0)));
  }

  @Test
  void cancel_byNonMember_returns403() throws Exception {
    Registered alice = register("alice@example.com", "Alice");
    Registered bob = register("bob@example.com", "Bob");
    Registered carol = register("carol@example.com", "Carol");
    befriend(alice, bob);
    String roomId = createRoom(alice);
    sendInvitation(alice, roomId, bob.id()).andExpect(status().isCreated());

    // Carol is not a member of Alice's room.
    mockMvc
        .perform(
            delete("/api/me/invitations/{roomId}/to/{inviteeUserId}", roomId, bob.id())
                .header(HttpHeaders.AUTHORIZATION, bearer(carol)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error", equalTo("NOT_ROOM_MEMBER")));
  }

  @Test
  void cancel_noInvitation_returns404() throws Exception {
    Registered alice = register("alice@example.com", "Alice");
    Registered bob = register("bob@example.com", "Bob");
    befriend(alice, bob);
    String roomId = createRoom(alice);
    // No invitation sent.

    mockMvc
        .perform(
            delete("/api/me/invitations/{roomId}/to/{inviteeUserId}", roomId, bob.id())
                .header(HttpHeaders.AUTHORIZATION, bearer(alice)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error", equalTo("INVITATION_NOT_FOUND")));
  }

  // --- per-user STOMP push -----------------------------------------------------------------

  @Test
  void send_pushesInvitationReceivedToAuthenticatedInviteeQueue() throws Exception {
    Registered alice = register("alice@example.com", "Alice");
    Registered bob = register("bob@example.com", "Bob");
    befriend(alice, bob);
    String roomId = createRoom(alice);

    // Bob connects AUTHENTICATED and subscribes to his private invitations queue.
    StompSession bobSession = connect("Bearer " + bob.token());
    BlockingQueue<String> pushes = subscribe(bobSession, "/user/queue/invitations");

    sendInvitation(alice, roomId, bob.id()).andExpect(status().isCreated());

    await()
        .atMost(AWAIT_TIMEOUT)
        .untilAsserted(
            () -> {
              String received = pushes.poll();
              assertThat(received)
                  .as("authenticated invitee should receive InvitationReceivedEvent")
                  .isNotNull();
              JsonNode event = objectMapper.readTree(received);
              assertThat(event.get("type").asText()).isEqualTo(InvitationReceivedEvent.TYPE);
              assertThat(event.get("roomId").asText()).isEqualTo(roomId);
              assertThat(event.get("inviterUserId").asText()).isEqualTo(alice.id().toString());
              assertThat(event.get("inviterDisplayName").asText()).isEqualTo("Alice");
            });
  }

  @Test
  void send_anonymousStompSession_doesNotReceivePush() throws Exception {
    Registered alice = register("alice@example.com", "Alice");
    Registered bob = register("bob@example.com", "Bob");
    befriend(alice, bob);
    String roomId = createRoom(alice);

    // A token-less (anonymous) STOMP session has no principal, so the user-destination cannot
    // resolve to it. Subscribing to the shared "/user/queue/invitations" yields nothing.
    StompSession anonymous = connect(null);
    BlockingQueue<String> pushes = subscribe(anonymous, "/user/queue/invitations");

    sendInvitation(alice, roomId, bob.id()).andExpect(status().isCreated());

    assertThat(pushes.poll(NO_RECEIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS))
        .as("anonymous session must not receive a per-user push")
        .isNull();
  }

  // --- 401 ---------------------------------------------------------------------------------

  @Test
  void everyEndpoint_withoutToken_returns401() throws Exception {
    UUID anyId = UUID.randomUUID();
    mockMvc
        .perform(
            post("/api/me/invitations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"roomId\":\"ABCDEF\",\"friendUserId\":\"" + anyId + "\"}"))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(get("/api/me/invitations")).andExpect(status().isUnauthorized());
    mockMvc
        .perform(post("/api/me/invitations/{roomId}/accept", "ABCDEF"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(delete("/api/me/invitations/{roomId}", "ABCDEF"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(delete("/api/me/invitations/{roomId}/to/{inviteeUserId}", "ABCDEF", anyId))
        .andExpect(status().isUnauthorized());
  }

  // --- regression --------------------------------------------------------------------------

  @Test
  void anonymousGameCreate_stillWorks_afterInvitationsFeature() throws Exception {
    mockMvc
        .perform(
            post("/api/rooms")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Guest\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.roomId").exists());
  }

  // --- helpers -----------------------------------------------------------------------------

  private ResultActions sendInvitation(Registered caller, String roomId, UUID friendUserId)
      throws Exception {
    return mockMvc.perform(
        post("/api/me/invitations")
            .header(HttpHeaders.AUTHORIZATION, bearer(caller))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                String.format(
                    "{\"roomId\":\"%s\",\"friendUserId\":\"%s\"}", roomId, friendUserId)));
  }

  private ResultActions accept(Registered caller, String roomId) throws Exception {
    return mockMvc.perform(
        post("/api/me/invitations/{roomId}/accept", roomId)
            .header(HttpHeaders.AUTHORIZATION, bearer(caller)));
  }

  /** Creates a FRIEND room owned by the caller and returns its id. */
  private String createRoom(Registered caller) throws Exception {
    return createRoomWithToken(caller).roomId();
  }

  /** Creates a FRIEND room owned by the caller and returns both its id and its join token. */
  private CreatedRoom createRoomWithToken(Registered caller) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/rooms")
                    .header(HttpHeaders.AUTHORIZATION, bearer(caller))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"displayName\":\"" + caller.displayName() + "\"}"))
            .andExpect(status().isCreated())
            .andReturn();
    JsonNode body = json(result);
    return new CreatedRoom(body.get("roomId").asText(), body.get("joinToken").asText());
  }

  /**
   * Joins {@code roomId} as {@code joiner} using the supplied token, filling the room to ACTIVE.
   */
  private void fillRoom(String roomId, String joinToken, Registered joiner) throws Exception {
    mockMvc
        .perform(
            post("/api/rooms/{id}/join", roomId)
                .header(HttpHeaders.AUTHORIZATION, bearer(joiner))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    String.format(
                        "{\"displayName\":\"%s\",\"joinToken\":\"%s\"}",
                        joiner.displayName(), joinToken)))
        .andExpect(status().isOk());
  }

  /** Establishes an ACCEPTED friendship between the two users via the REST surface. */
  private void befriend(Registered a, Registered b) throws Exception {
    mockMvc
        .perform(
            post("/api/me/friends/requests")
                .header(HttpHeaders.AUTHORIZATION, bearer(a))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"friendCode\":\"" + b.friendCode() + "\"}"))
        .andExpect(status().isCreated());
    MvcResult incoming =
        mockMvc
            .perform(
                get("/api/me/friends/requests/incoming")
                    .header(HttpHeaders.AUTHORIZATION, bearer(b)))
            .andExpect(status().isOk())
            .andReturn();
    UUID requestId =
        UUID.fromString(json(incoming).get("content").get(0).get("requestId").asText());
    mockMvc
        .perform(
            post("/api/me/friends/requests/{id}/accept", requestId)
                .header(HttpHeaders.AUTHORIZATION, bearer(b)))
        .andExpect(status().isNoContent());
  }

  private StompSession connect(String authorizationHeaderOrNull) throws Exception {
    StompHeaders connectHeaders = new StompHeaders();
    if (authorizationHeaderOrNull != null) {
      connectHeaders.add("Authorization", authorizationHeaderOrNull);
    }
    return stompClient
        .connectAsync(
            "ws://localhost:" + port + "/ws",
            new WebSocketHttpHeaders(),
            connectHeaders,
            new StompSessionHandlerAdapter() {})
        .get(AWAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
  }

  private BlockingQueue<String> subscribe(StompSession session, String destination)
      throws InterruptedException {
    BlockingQueue<String> queue = new ArrayBlockingQueue<>(8);
    StompHeaders headers = new StompHeaders();
    headers.setDestination(destination);
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
    // Give the broker a beat to register the subscription before the trigger fires.
    Thread.sleep(800L);
    return queue;
  }

  private static String bearer(Registered user) {
    return "Bearer " + user.token();
  }

  private JsonNode json(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }

  private Registered register(String email, String displayName) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        String.format(
                            "{\"email\":\"%s\",\"password\":\"supersecret\",\"displayName\":\"%s\"}",
                            email, displayName)))
            .andExpect(status().isCreated())
            .andReturn();
    JsonNode body = json(result);
    UUID id = UUID.fromString(body.get("user").get("id").asText());
    String token = body.get("token").asText();
    MvcResult codeResult =
        mockMvc
            .perform(
                get("/api/me/friend-code").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn();
    String friendCode = json(codeResult).get("friendCode").asText();
    return new Registered(id, token, friendCode, displayName);
  }

  private record Registered(UUID id, String token, String friendCode, String displayName) {}

  private record CreatedRoom(String roomId, String joinToken) {}
}
