package io.github.dariogguillen.chess.web.me;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dariogguillen.chess.TestcontainersConfiguration;
import io.github.dariogguillen.chess.persistence.FriendshipRepository;
import io.github.dariogguillen.chess.persistence.UserRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * End-to-end integration tests for the friends-list feature (feature 23.8). Boots the full Spring
 * context with Testcontainers Postgres + Redis so the JWT filter chain, the V3 migration (friend
 * code + friendships table + the unordered-pair UNIQUE index), and the entity-join projections all
 * participate.
 *
 * <p>{@link #cleanState()} deletes {@code friendships} before {@code users} — child before parent —
 * so the FK to {@code users(id)} does not block the wipe, mirroring the cross-IT cleanup ordering
 * in {@code MyGamesIT}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class FriendshipIT {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserRepository users;
  @Autowired private FriendshipRepository friendships;

  @BeforeEach
  void cleanState() {
    friendships.deleteAll();
    users.deleteAll();
  }

  // --- friend code -------------------------------------------------------------------------

  @Test
  void friendCode_roundTrip_returnsAStableEightCharCode() throws Exception {
    RegisteredUser alice = register("alice@example.com", "Alice");

    MvcResult result =
        mockMvc
            .perform(get("/api/me/friend-code").header(HttpHeaders.AUTHORIZATION, bearer(alice)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.friendCode").exists())
            .andReturn();

    String code = json(result).get("friendCode").asText();
    assertThat(code).hasSize(8);
    assertThat(code).isEqualTo(alice.friendCode());
  }

  // --- send request ------------------------------------------------------------------------

  @Test
  void sendRequest_happyPath_creates201AndShowsUpInBothLists() throws Exception {
    RegisteredUser alice = register("alice@example.com", "Alice");
    RegisteredUser bob = register("bob@example.com", "Bob");

    sendRequest(alice, bob.friendCode()).andExpect(status().isCreated());

    // Alice sees it as outgoing.
    mockMvc
        .perform(
            get("/api/me/friends/requests/outgoing")
                .header(HttpHeaders.AUTHORIZATION, bearer(alice)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content", hasSize(1)))
        .andExpect(jsonPath("$.content[0].userId", equalTo(bob.id().toString())))
        .andExpect(jsonPath("$.content[0].displayName", equalTo("Bob")));

    // Bob sees it as incoming.
    mockMvc
        .perform(
            get("/api/me/friends/requests/incoming").header(HttpHeaders.AUTHORIZATION, bearer(bob)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content", hasSize(1)))
        .andExpect(jsonPath("$.content[0].userId", equalTo(alice.id().toString())))
        .andExpect(jsonPath("$.content[0].displayName", equalTo("Alice")));
  }

  @Test
  void sendRequest_unknownCode_returns404FriendCodeNotFound() throws Exception {
    RegisteredUser alice = register("alice@example.com", "Alice");

    sendRequest(alice, "ZZZZZZZZ")
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error", equalTo("FRIEND_CODE_NOT_FOUND")));
  }

  @Test
  void sendRequest_ownCode_returns422SelfFriendship() throws Exception {
    RegisteredUser alice = register("alice@example.com", "Alice");

    sendRequest(alice, alice.friendCode())
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.error", equalTo("SELF_FRIENDSHIP")));
  }

  @Test
  void sendRequest_alreadyFriends_returns409AlreadyFriends() throws Exception {
    RegisteredUser alice = register("alice@example.com", "Alice");
    RegisteredUser bob = register("bob@example.com", "Bob");

    UUID requestId = sendAndGetRequestId(alice, bob);
    accept(bob, requestId).andExpect(status().isNoContent());

    sendRequest(alice, bob.friendCode())
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error", equalTo("ALREADY_FRIENDS")));
  }

  @Test
  void sendRequest_duplicateSameDirection_returns409Duplicate() throws Exception {
    RegisteredUser alice = register("alice@example.com", "Alice");
    RegisteredUser bob = register("bob@example.com", "Bob");

    sendRequest(alice, bob.friendCode()).andExpect(status().isCreated());
    sendRequest(alice, bob.friendCode())
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error", equalTo("DUPLICATE_FRIEND_REQUEST")));
  }

  @Test
  void sendRequest_duplicateOppositeDirection_returns409Duplicate() throws Exception {
    RegisteredUser alice = register("alice@example.com", "Alice");
    RegisteredUser bob = register("bob@example.com", "Bob");

    // Alice → Bob exists; Bob → Alice must be rejected as a duplicate (unordered-pair uniqueness).
    sendRequest(alice, bob.friendCode()).andExpect(status().isCreated());
    sendRequest(bob, alice.friendCode())
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error", equalTo("DUPLICATE_FRIEND_REQUEST")));
  }

  @Test
  void sendRequest_blankCode_returns400Validation() throws Exception {
    RegisteredUser alice = register("alice@example.com", "Alice");

    mockMvc
        .perform(
            post("/api/me/friends/requests")
                .header(HttpHeaders.AUTHORIZATION, bearer(alice))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"friendCode\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error", equalTo("VALIDATION_FAILED")));
  }

  // --- accept ------------------------------------------------------------------------------

  @Test
  void accept_byAddressee_movesToFriendsListForBothParties() throws Exception {
    RegisteredUser alice = register("alice@example.com", "Alice");
    RegisteredUser bob = register("bob@example.com", "Bob");

    UUID requestId = sendAndGetRequestId(alice, bob);
    accept(bob, requestId).andExpect(status().isNoContent());

    // Both now see each other as a friend.
    mockMvc
        .perform(get("/api/me/friends").header(HttpHeaders.AUTHORIZATION, bearer(alice)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content", hasSize(1)))
        .andExpect(jsonPath("$.content[0].userId", equalTo(bob.id().toString())))
        .andExpect(jsonPath("$.content[0].displayName", equalTo("Bob")))
        .andExpect(jsonPath("$.content[0].friendCode", equalTo(bob.friendCode())))
        .andExpect(jsonPath("$.content[0].friendsSince").exists());

    mockMvc
        .perform(get("/api/me/friends").header(HttpHeaders.AUTHORIZATION, bearer(bob)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content", hasSize(1)))
        .andExpect(jsonPath("$.content[0].userId", equalTo(alice.id().toString())));

    // The pending lists are now empty for both.
    mockMvc
        .perform(
            get("/api/me/friends/requests/incoming").header(HttpHeaders.AUTHORIZATION, bearer(bob)))
        .andExpect(jsonPath("$.content", hasSize(0)));
    mockMvc
        .perform(
            get("/api/me/friends/requests/outgoing")
                .header(HttpHeaders.AUTHORIZATION, bearer(alice)))
        .andExpect(jsonPath("$.content", hasSize(0)));
  }

  @Test
  void accept_byNonAddressee_returns404NoLeak() throws Exception {
    RegisteredUser alice = register("alice@example.com", "Alice");
    RegisteredUser bob = register("bob@example.com", "Bob");
    RegisteredUser carol = register("carol@example.com", "Carol");

    UUID requestId = sendAndGetRequestId(alice, bob);

    // The requester (Alice) cannot accept her own outgoing request.
    accept(alice, requestId)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error", equalTo("FRIEND_REQUEST_NOT_FOUND")));

    // An unrelated third party (Carol) gets the same 404 — no existence leak.
    accept(carol, requestId)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error", equalTo("FRIEND_REQUEST_NOT_FOUND")));
  }

  @Test
  void accept_unknownId_returns404() throws Exception {
    RegisteredUser bob = register("bob@example.com", "Bob");

    accept(bob, UUID.randomUUID())
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error", equalTo("FRIEND_REQUEST_NOT_FOUND")));
  }

  // --- reject / cancel ---------------------------------------------------------------------

  @Test
  void delete_byAddressee_rejectsAndClearsTheRequest() throws Exception {
    RegisteredUser alice = register("alice@example.com", "Alice");
    RegisteredUser bob = register("bob@example.com", "Bob");

    UUID requestId = sendAndGetRequestId(alice, bob);
    deleteRequest(bob, requestId).andExpect(status().isNoContent());

    mockMvc
        .perform(
            get("/api/me/friends/requests/incoming").header(HttpHeaders.AUTHORIZATION, bearer(bob)))
        .andExpect(jsonPath("$.content", hasSize(0)));

    // No tombstone — Alice can re-request and it succeeds.
    sendRequest(alice, bob.friendCode()).andExpect(status().isCreated());
  }

  @Test
  void delete_byRequester_cancelsTheRequest() throws Exception {
    RegisteredUser alice = register("alice@example.com", "Alice");
    RegisteredUser bob = register("bob@example.com", "Bob");

    UUID requestId = sendAndGetRequestId(alice, bob);
    deleteRequest(alice, requestId).andExpect(status().isNoContent());

    mockMvc
        .perform(
            get("/api/me/friends/requests/outgoing")
                .header(HttpHeaders.AUTHORIZATION, bearer(alice)))
        .andExpect(jsonPath("$.content", hasSize(0)));
  }

  @Test
  void delete_byNonParticipant_returns404NoLeak() throws Exception {
    RegisteredUser alice = register("alice@example.com", "Alice");
    RegisteredUser bob = register("bob@example.com", "Bob");
    RegisteredUser carol = register("carol@example.com", "Carol");

    UUID requestId = sendAndGetRequestId(alice, bob);

    deleteRequest(carol, requestId)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error", equalTo("FRIEND_REQUEST_NOT_FOUND")));
  }

  // --- remove friend -----------------------------------------------------------------------

  @Test
  void removeFriend_byEitherParty_deletesTheAcceptedFriendship() throws Exception {
    RegisteredUser alice = register("alice@example.com", "Alice");
    RegisteredUser bob = register("bob@example.com", "Bob");

    UUID requestId = sendAndGetRequestId(alice, bob);
    accept(bob, requestId).andExpect(status().isNoContent());

    // Bob removes Alice.
    mockMvc
        .perform(
            delete("/api/me/friends/{userId}", alice.id())
                .header(HttpHeaders.AUTHORIZATION, bearer(bob)))
        .andExpect(status().isNoContent());

    // Neither sees the other anymore.
    mockMvc
        .perform(get("/api/me/friends").header(HttpHeaders.AUTHORIZATION, bearer(alice)))
        .andExpect(jsonPath("$.content", hasSize(0)));
    mockMvc
        .perform(get("/api/me/friends").header(HttpHeaders.AUTHORIZATION, bearer(bob)))
        .andExpect(jsonPath("$.content", hasSize(0)));
  }

  @Test
  void removeFriend_whenNotFriends_returns404FriendNotFound() throws Exception {
    RegisteredUser alice = register("alice@example.com", "Alice");
    RegisteredUser bob = register("bob@example.com", "Bob");

    mockMvc
        .perform(
            delete("/api/me/friends/{userId}", bob.id())
                .header(HttpHeaders.AUTHORIZATION, bearer(alice)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error", equalTo("FRIEND_NOT_FOUND")));
  }

  // --- pagination --------------------------------------------------------------------------

  @Test
  void listFriends_pagination_respectsPageAndSize() throws Exception {
    RegisteredUser alice = register("alice@example.com", "Alice");
    // Alice accepts five friends.
    for (int i = 0; i < 5; i++) {
      RegisteredUser friend = register("friend" + i + "@example.com", "Friend" + i);
      UUID requestId = sendAndGetRequestId(alice, friend);
      accept(friend, requestId).andExpect(status().isNoContent());
    }

    mockMvc
        .perform(
            get("/api/me/friends?page=0&size=2").header(HttpHeaders.AUTHORIZATION, bearer(alice)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content", hasSize(2)))
        .andExpect(jsonPath("$.totalElements", equalTo(5)))
        .andExpect(jsonPath("$.totalPages", equalTo(3)))
        .andExpect(jsonPath("$.size", equalTo(2)));

    mockMvc
        .perform(
            get("/api/me/friends?page=2&size=2").header(HttpHeaders.AUTHORIZATION, bearer(alice)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content", hasSize(1)))
        .andExpect(jsonPath("$.number", equalTo(2)));
  }

  @Test
  void list_invalidPagination_returns400() throws Exception {
    RegisteredUser alice = register("alice@example.com", "Alice");

    mockMvc
        .perform(get("/api/me/friends?size=101").header(HttpHeaders.AUTHORIZATION, bearer(alice)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error", equalTo("VALIDATION_FAILED")));

    mockMvc
        .perform(get("/api/me/friends?page=-1").header(HttpHeaders.AUTHORIZATION, bearer(alice)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error", equalTo("VALIDATION_FAILED")));
  }

  // --- 401 on every endpoint ---------------------------------------------------------------

  @Test
  void everyEndpoint_withoutToken_returns401() throws Exception {
    UUID anyId = UUID.randomUUID();
    mockMvc.perform(get("/api/me/friend-code")).andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            post("/api/me/friends/requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"friendCode\":\"ABCDEFGH\"}"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(post("/api/me/friends/requests/{id}/accept", anyId))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(delete("/api/me/friends/requests/{id}", anyId))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(delete("/api/me/friends/{userId}", anyId)).andExpect(status().isUnauthorized());
    mockMvc.perform(get("/api/me/friends")).andExpect(status().isUnauthorized());
    mockMvc.perform(get("/api/me/friends/requests/incoming")).andExpect(status().isUnauthorized());
    mockMvc.perform(get("/api/me/friends/requests/outgoing")).andExpect(status().isUnauthorized());
  }

  // --- regression: anonymous game-create still works ---------------------------------------

  @Test
  void anonymousGameCreate_stillWorks_afterFriendsFeature() throws Exception {
    mockMvc
        .perform(
            post("/api/rooms")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Guest\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.roomId").exists());
  }

  // --- helpers -----------------------------------------------------------------------------

  private ResultActions sendRequest(RegisteredUser caller, String friendCode) throws Exception {
    return mockMvc.perform(
        post("/api/me/friends/requests")
            .header(HttpHeaders.AUTHORIZATION, bearer(caller))
            .contentType(MediaType.APPLICATION_JSON)
            .content(String.format("{\"friendCode\":\"%s\"}", friendCode)));
  }

  private ResultActions accept(RegisteredUser caller, UUID requestId) throws Exception {
    return mockMvc.perform(
        post("/api/me/friends/requests/{id}/accept", requestId)
            .header(HttpHeaders.AUTHORIZATION, bearer(caller)));
  }

  private ResultActions deleteRequest(RegisteredUser caller, UUID requestId) throws Exception {
    return mockMvc.perform(
        delete("/api/me/friends/requests/{id}", requestId)
            .header(HttpHeaders.AUTHORIZATION, bearer(caller)));
  }

  /** Sends alice→friend and returns the request id read from friend's incoming list. */
  private UUID sendAndGetRequestId(RegisteredUser requester, RegisteredUser addressee)
      throws Exception {
    sendRequest(requester, addressee.friendCode()).andExpect(status().isCreated());
    MvcResult incoming =
        mockMvc
            .perform(
                get("/api/me/friends/requests/incoming")
                    .header(HttpHeaders.AUTHORIZATION, bearer(addressee)))
            .andExpect(status().isOk())
            .andReturn();
    return UUID.fromString(json(incoming).get("content").get(0).get("requestId").asText());
  }

  private static String bearer(RegisteredUser user) {
    return "Bearer " + user.token();
  }

  private JsonNode json(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }

  private RegisteredUser register(String email, String displayName) throws Exception {
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

    // Read the friend code via the authenticated endpoint (register's response does not carry it).
    MvcResult codeResult =
        mockMvc
            .perform(
                get("/api/me/friend-code").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn();
    String friendCode = json(codeResult).get("friendCode").asText();
    return new RegisteredUser(id, token, friendCode);
  }

  private record RegisteredUser(UUID id, String token, String friendCode) {}
}
