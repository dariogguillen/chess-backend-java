package io.github.dariogguillen.chess.web.room;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.isOneOf;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dariogguillen.chess.TestcontainersConfiguration;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Integration tests for {@code POST /api/rooms} and {@code POST /api/rooms/{id}/join}. Boots the
 * full Spring context (including the testcontainers wiring so the production beans we don't
 * exercise here still resolve) and drives the endpoints through {@link MockMvc}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class RoomControllerIT {

  private static final String CODE_PATTERN = "^[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{6}$";
  private static final String UUID_PATTERN =
      "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @Test
  void createRoom_returns201WithRoomAndPlayerIds() throws Exception {
    mockMvc
        .perform(
            post("/api/rooms")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Alice\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.roomId", matchesPattern(CODE_PATTERN)))
        .andExpect(jsonPath("$.playerId", matchesPattern(UUID_PATTERN)))
        .andExpect(jsonPath("$.role", equalTo("WHITE")))
        .andExpect(jsonPath("$.gameId", nullValue()));
  }

  @Test
  void createRoom_preferredSideWhite_returnsRoleWhite() throws Exception {
    mockMvc
        .perform(
            post("/api/rooms")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Alice\",\"preferredSide\":\"WHITE\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.role", equalTo("WHITE")));
  }

  @Test
  void createRoom_preferredSideBlack_returnsRoleBlack() throws Exception {
    mockMvc
        .perform(
            post("/api/rooms")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Alice\",\"preferredSide\":\"BLACK\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.role", equalTo("BLACK")));
  }

  @Test
  void createRoom_preferredSideOmitted_defaultsToWhite() throws Exception {
    // Regression for the existing frontend version that does not send the field.
    mockMvc
        .perform(
            post("/api/rooms")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Alice\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.role", equalTo("WHITE")));
  }

  @Test
  void createRoom_preferredSideRandom_returnsConcreteSideNeverRandom() throws Exception {
    mockMvc
        .perform(
            post("/api/rooms")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Alice\",\"preferredSide\":\"RANDOM\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.role", isOneOf("WHITE", "BLACK")));
  }

  @Test
  void createRoom_returnsNonNullJoinToken() throws Exception {
    // Feature 22.7: the create response is the only place the secret join token surfaces.
    mockMvc
        .perform(
            post("/api/rooms")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Alice\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.joinToken", not(nullValue())))
        .andExpect(jsonPath("$.joinToken", matchesPattern(UUID_PATTERN)));
  }

  @Test
  void joinRoom_whiteCreator_joinerGetsBlack() throws Exception {
    CreatedRoom room = createRoom("Alice", "WHITE");

    mockMvc
        .perform(
            post("/api/rooms/{id}/join", room.roomId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(joinBody("Bob", room.joinToken())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.role", equalTo("BLACK")));
  }

  @Test
  void joinRoom_blackCreator_joinerGetsWhite() throws Exception {
    CreatedRoom room = createRoom("Alice", "BLACK");

    mockMvc
        .perform(
            post("/api/rooms/{id}/join", room.roomId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(joinBody("Bob", room.joinToken())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.role", equalTo("WHITE")));
  }

  @Test
  void joinRoom_returns200WithGameIdAndNullJoinToken() throws Exception {
    CreatedRoom room = createRoom("Alice");

    mockMvc
        .perform(
            post("/api/rooms/{id}/join", room.roomId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(joinBody("Bob", room.joinToken())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.roomId", equalTo(room.roomId())))
        .andExpect(jsonPath("$.playerId", matchesPattern(UUID_PATTERN)))
        .andExpect(jsonPath("$.role", equalTo("BLACK")))
        .andExpect(jsonPath("$.gameId", matchesPattern(UUID_PATTERN)))
        .andExpect(jsonPath("$.gameId", not(nullValue())))
        // The joiner is already in; the token is never echoed on the join response.
        .andExpect(jsonPath("$.joinToken", nullValue()));
  }

  @Test
  void joinRoom_wrongToken_returns403() throws Exception {
    CreatedRoom room = createRoom("Alice");

    mockMvc
        .perform(
            post("/api/rooms/{id}/join", room.roomId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(joinBody("Bob", "not-the-real-token")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error", equalTo("INVALID_JOIN_TOKEN")))
        .andExpect(jsonPath("$.message").exists())
        .andExpect(jsonPath("$.timestamp").exists());
  }

  @Test
  void joinRoom_noToken_returns403() throws Exception {
    // A spectator who only holds the roomId (no token) cannot grab the player slot.
    CreatedRoom room = createRoom("Alice");

    mockMvc
        .perform(
            post("/api/rooms/{id}/join", room.roomId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Bob\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error", equalTo("INVALID_JOIN_TOKEN")));
  }

  @Test
  void joinRoom_lowercaseRoomId_returns200WithCanonicalUppercaseInBody() throws Exception {
    CreatedRoom room = createRoom("Alice");
    String lowercaseId = room.roomId().toLowerCase(Locale.ROOT);

    mockMvc
        .perform(
            post("/api/rooms/{id}/join", lowercaseId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(joinBody("Bob", room.joinToken())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.roomId", equalTo(room.roomId())))
        .andExpect(jsonPath("$.roomId", not(equalTo(lowercaseId))))
        .andExpect(jsonPath("$.gameId", matchesPattern(UUID_PATTERN)))
        .andExpect(jsonPath("$.gameId", not(nullValue())));
  }

  @Test
  void joinRoom_unknownRoom_returns404() throws Exception {
    mockMvc
        .perform(
            post("/api/rooms/{id}/join", "NOPE99")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Bob\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error", equalTo("ROOM_NOT_FOUND")))
        .andExpect(jsonPath("$.message").exists())
        .andExpect(jsonPath("$.timestamp").exists());
  }

  @Test
  void joinRoom_fullRoom_returns409() throws Exception {
    // From the public HTTP API, "full" and "already started" are the same transition: the join
    // that takes the room to 2 players also flips it from WAITING_FOR_PLAYER to ACTIVE. The
    // service's compute lambda checks the size invariant first, so a third joiner hits
    // RoomFullException (HTTP 409 / ROOM_FULL) — not RoomAlreadyStarted. The third joiner supplies
    // the correct token so the 409 (not a 403) is the assertion under test.
    CreatedRoom room = createRoom("Alice");
    mockMvc
        .perform(
            post("/api/rooms/{id}/join", room.roomId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(joinBody("Bob", room.joinToken())))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            post("/api/rooms/{id}/join", room.roomId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(joinBody("Carol", room.joinToken())))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error", equalTo("ROOM_FULL")))
        .andExpect(jsonPath("$.message").exists())
        .andExpect(jsonPath("$.timestamp").exists());
  }

  @Test
  void createRoom_blankDisplayName_returns400() throws Exception {
    mockMvc
        .perform(
            post("/api/rooms")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error", equalTo("VALIDATION_FAILED")))
        .andExpect(jsonPath("$.message").exists());
  }

  @Test
  void createRoom_malformedJson_returns400() throws Exception {
    mockMvc
        .perform(post("/api/rooms").contentType(MediaType.APPLICATION_JSON).content("{not-json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error", equalTo("MALFORMED_REQUEST")));
  }

  @Test
  void createRoom_botEloBelowMin_returns400() throws Exception {
    // 1319 is one below the engine's UCI_Elo floor (1320); Bean Validation rejects it.
    mockMvc
        .perform(
            post("/api/rooms")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Alice\",\"opponentKind\":\"BOT\",\"botElo\":1319}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error", equalTo("VALIDATION_FAILED")))
        .andExpect(jsonPath("$.message").exists());
  }

  @Test
  void createRoom_botEloAboveMax_returns400() throws Exception {
    // 3191 is one above the engine's UCI_Elo ceiling (3190); Bean Validation rejects it.
    mockMvc
        .perform(
            post("/api/rooms")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Alice\",\"opponentKind\":\"BOT\",\"botElo\":3191}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error", equalTo("VALIDATION_FAILED")))
        .andExpect(jsonPath("$.message").exists());
  }

  @Test
  void createRoom_botEloInRange_returns201() throws Exception {
    mockMvc
        .perform(
            post("/api/rooms")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Alice\",\"opponentKind\":\"BOT\",\"botElo\":2200}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.gameId", not(nullValue())));
  }

  @Test
  void createRoom_botOpponentNullElo_returns201() throws Exception {
    // Omitting botElo on a BOT room is accepted — the server applies its configured default.
    mockMvc
        .perform(
            post("/api/rooms")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Alice\",\"opponentKind\":\"BOT\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.gameId", not(nullValue())));
  }

  private CreatedRoom createRoom(String displayName) throws Exception {
    return createRoomFromBody("{\"displayName\":\"" + displayName + "\"}");
  }

  private CreatedRoom createRoom(String displayName, String preferredSide) throws Exception {
    return createRoomFromBody(
        "{\"displayName\":\"" + displayName + "\",\"preferredSide\":\"" + preferredSide + "\"}");
  }

  private CreatedRoom createRoomFromBody(String requestBody) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/rooms").contentType(MediaType.APPLICATION_JSON).content(requestBody))
            .andExpect(status().isCreated())
            .andReturn();
    JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
    return new CreatedRoom(body.get("roomId").asText(), body.get("joinToken").asText());
  }

  /** Builds a join request body carrying the display name and the secret join token. */
  private static String joinBody(String displayName, String joinToken) {
    return "{\"displayName\":\"" + displayName + "\",\"joinToken\":\"" + joinToken + "\"}";
  }

  /** The roomId + secret joinToken a create call returns; the token is needed to join. */
  private record CreatedRoom(String roomId, String joinToken) {}
}
