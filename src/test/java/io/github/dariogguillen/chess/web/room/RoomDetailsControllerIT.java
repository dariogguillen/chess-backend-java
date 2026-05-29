package io.github.dariogguillen.chess.web.room;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dariogguillen.chess.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Integration tests for {@code GET /api/rooms/{id}}. Exercises both phases of the room lifecycle
 * (WAITING_FOR_PLAYER and ACTIVE) and the 404 path. The endpoint is the REST companion of {@code
 * /topic/rooms/{roomId}} — covered as a STOMP IT in {@code RoomLifecycleIT}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class RoomDetailsControllerIT {

  private static final String UUID_PATTERN =
      "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @Test
  void getRoom_waitingForPlayer_returnsExpectedShape() throws Exception {
    String roomId = createRoom("Alice").roomId();

    mockMvc
        .perform(get("/api/rooms/{id}", roomId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.roomId", equalTo(roomId)))
        .andExpect(jsonPath("$.status", equalTo("WAITING_FOR_PLAYER")))
        .andExpect(jsonPath("$.gameId", nullValue()))
        // Feature 22.7: the watch path must never leak the secret join token.
        .andExpect(jsonPath("$.joinToken").doesNotExist())
        .andExpect(jsonPath("$.players", hasSize(1)))
        .andExpect(jsonPath("$.players[0].id", matchesPattern(UUID_PATTERN)))
        .andExpect(jsonPath("$.players[0].displayName", equalTo("Alice")))
        .andExpect(jsonPath("$.players[0].role", equalTo("WHITE")));
  }

  @Test
  void getRoom_active_returnsExpectedShapeWithBothPlayers() throws Exception {
    CreatedRoom room = createRoom("Alice");
    joinRoom(room.roomId(), "Bob", room.joinToken());

    mockMvc
        .perform(get("/api/rooms/{id}", room.roomId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.roomId", equalTo(room.roomId())))
        .andExpect(jsonPath("$.status", equalTo("ACTIVE")))
        .andExpect(jsonPath("$.gameId", matchesPattern(UUID_PATTERN)))
        .andExpect(jsonPath("$.gameId", not(nullValue())))
        // Feature 22.7: still no token leak once the room is ACTIVE.
        .andExpect(jsonPath("$.joinToken").doesNotExist())
        .andExpect(jsonPath("$.players", hasSize(2)))
        .andExpect(jsonPath("$.players[0].displayName", equalTo("Alice")))
        .andExpect(jsonPath("$.players[0].role", equalTo("WHITE")))
        .andExpect(jsonPath("$.players[1].displayName", equalTo("Bob")))
        .andExpect(jsonPath("$.players[1].role", equalTo("BLACK")));
  }

  @Test
  void getRoom_blackCreator_waitingForPlayer_reflectsChosenSide() throws Exception {
    String roomId = createRoom("Alice", "BLACK").roomId();

    mockMvc
        .perform(get("/api/rooms/{id}", roomId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status", equalTo("WAITING_FOR_PLAYER")))
        .andExpect(jsonPath("$.players", hasSize(1)))
        .andExpect(jsonPath("$.players[0].displayName", equalTo("Alice")))
        .andExpect(jsonPath("$.players[0].role", equalTo("BLACK")));
  }

  @Test
  void getRoom_blackCreator_active_reflectsChosenSideForBothPlayers() throws Exception {
    CreatedRoom room = createRoom("Alice", "BLACK");
    joinRoom(room.roomId(), "Bob", room.joinToken());

    mockMvc
        .perform(get("/api/rooms/{id}", room.roomId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status", equalTo("ACTIVE")))
        .andExpect(jsonPath("$.players", hasSize(2)))
        .andExpect(jsonPath("$.players[0].displayName", equalTo("Alice")))
        .andExpect(jsonPath("$.players[0].role", equalTo("BLACK")))
        .andExpect(jsonPath("$.players[1].displayName", equalTo("Bob")))
        .andExpect(jsonPath("$.players[1].role", equalTo("WHITE")));
  }

  @Test
  void getRoom_unknownId_returns404RoomNotFound() throws Exception {
    mockMvc
        .perform(get("/api/rooms/{id}", "NOPE99"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error", equalTo("ROOM_NOT_FOUND")))
        .andExpect(jsonPath("$.message").exists())
        .andExpect(jsonPath("$.timestamp").exists());
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

  private void joinRoom(String roomId, String displayName, String joinToken) throws Exception {
    mockMvc
        .perform(
            post("/api/rooms/{id}/join", roomId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"displayName\":\""
                        + displayName
                        + "\",\"joinToken\":\""
                        + joinToken
                        + "\"}"))
        .andExpect(status().isOk());
  }

  /** The roomId + secret joinToken a create call returns; the token is needed to join. */
  private record CreatedRoom(String roomId, String joinToken) {}
}
