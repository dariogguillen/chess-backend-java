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
  void joinRoom_whiteCreator_joinerGetsBlack() throws Exception {
    String roomId = createRoomAndReturnId("Alice", "WHITE");

    mockMvc
        .perform(
            post("/api/rooms/{id}/join", roomId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Bob\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.role", equalTo("BLACK")));
  }

  @Test
  void joinRoom_blackCreator_joinerGetsWhite() throws Exception {
    String roomId = createRoomAndReturnId("Alice", "BLACK");

    mockMvc
        .perform(
            post("/api/rooms/{id}/join", roomId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Bob\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.role", equalTo("WHITE")));
  }

  @Test
  void joinRoom_returns200WithGameId() throws Exception {
    String roomId = createRoomAndReturnId("Alice");

    mockMvc
        .perform(
            post("/api/rooms/{id}/join", roomId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Bob\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.roomId", equalTo(roomId)))
        .andExpect(jsonPath("$.playerId", matchesPattern(UUID_PATTERN)))
        .andExpect(jsonPath("$.role", equalTo("BLACK")))
        .andExpect(jsonPath("$.gameId", matchesPattern(UUID_PATTERN)))
        .andExpect(jsonPath("$.gameId", not(nullValue())));
  }

  @Test
  void joinRoom_lowercaseRoomId_returns200WithCanonicalUppercaseInBody() throws Exception {
    String roomId = createRoomAndReturnId("Alice");
    String lowercaseId = roomId.toLowerCase(Locale.ROOT);

    mockMvc
        .perform(
            post("/api/rooms/{id}/join", lowercaseId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Bob\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.roomId", equalTo(roomId)))
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
    // RoomFullException (HTTP 409 / ROOM_FULL) — not RoomAlreadyStarted.
    String roomId = createRoomAndReturnId("Alice");
    mockMvc
        .perform(
            post("/api/rooms/{id}/join", roomId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Bob\"}"))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            post("/api/rooms/{id}/join", roomId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Carol\"}"))
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

  private String createRoomAndReturnId(String displayName) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/rooms")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"displayName\":\"" + displayName + "\"}"))
            .andExpect(status().isCreated())
            .andReturn();
    JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
    return body.get("roomId").asText();
  }

  private String createRoomAndReturnId(String displayName, String preferredSide) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/rooms")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"displayName\":\""
                            + displayName
                            + "\",\"preferredSide\":\""
                            + preferredSide
                            + "\"}"))
            .andExpect(status().isCreated())
            .andReturn();
    JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
    return body.get("roomId").asText();
  }
}
