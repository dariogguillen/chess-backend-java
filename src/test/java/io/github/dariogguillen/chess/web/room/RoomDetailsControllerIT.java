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
    String roomId = createRoomAndReturnId("Alice");

    mockMvc
        .perform(get("/api/rooms/{id}", roomId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.roomId", equalTo(roomId)))
        .andExpect(jsonPath("$.status", equalTo("WAITING_FOR_PLAYER")))
        .andExpect(jsonPath("$.gameId", nullValue()))
        .andExpect(jsonPath("$.players", hasSize(1)))
        .andExpect(jsonPath("$.players[0].id", matchesPattern(UUID_PATTERN)))
        .andExpect(jsonPath("$.players[0].displayName", equalTo("Alice")))
        .andExpect(jsonPath("$.players[0].role", equalTo("WHITE")));
  }

  @Test
  void getRoom_active_returnsExpectedShapeWithBothPlayers() throws Exception {
    String roomId = createRoomAndReturnId("Alice");
    joinRoom(roomId, "Bob");

    mockMvc
        .perform(get("/api/rooms/{id}", roomId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.roomId", equalTo(roomId)))
        .andExpect(jsonPath("$.status", equalTo("ACTIVE")))
        .andExpect(jsonPath("$.gameId", matchesPattern(UUID_PATTERN)))
        .andExpect(jsonPath("$.gameId", not(nullValue())))
        .andExpect(jsonPath("$.players", hasSize(2)))
        .andExpect(jsonPath("$.players[0].displayName", equalTo("Alice")))
        .andExpect(jsonPath("$.players[0].role", equalTo("WHITE")))
        .andExpect(jsonPath("$.players[1].displayName", equalTo("Bob")))
        .andExpect(jsonPath("$.players[1].role", equalTo("BLACK")));
  }

  @Test
  void getRoom_blackCreator_waitingForPlayer_reflectsChosenSide() throws Exception {
    String roomId = createRoomAndReturnId("Alice", "BLACK");

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
    String roomId = createRoomAndReturnId("Alice", "BLACK");
    joinRoom(roomId, "Bob");

    mockMvc
        .perform(get("/api/rooms/{id}", roomId))
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

  private void joinRoom(String roomId, String displayName) throws Exception {
    mockMvc
        .perform(
            post("/api/rooms/{id}/join", roomId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"" + displayName + "\"}"))
        .andExpect(status().isOk());
  }
}
