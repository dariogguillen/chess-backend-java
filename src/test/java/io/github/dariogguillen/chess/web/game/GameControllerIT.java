package io.github.dariogguillen.chess.web.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dariogguillen.chess.TestcontainersConfiguration;
import io.github.dariogguillen.chess.domain.GameStatus;
import io.github.dariogguillen.chess.persistence.GameEntity;
import io.github.dariogguillen.chess.persistence.GameHistoryRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration tests for {@code GET /api/games/{id}} and {@code POST /api/games/{id}/moves}. Boots
 * the full Spring context (Testcontainers wiring so production beans we don't exercise here still
 * resolve) and drives the endpoints through {@link MockMvc}. Each test that needs an existing game
 * creates a room and joins it via the room endpoints — the resulting {@code gameId} plus the two
 * synthesised player ids drive the rest of the scenario.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class GameControllerIT {

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @Autowired private GameHistoryRepository gameHistoryRepository;

  @Test
  void getGame_unknownId_returns404() throws Exception {
    mockMvc
        .perform(get("/api/games/{id}", UUID.randomUUID()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error", equalTo("GAME_NOT_FOUND")))
        .andExpect(jsonPath("$.message").exists())
        .andExpect(jsonPath("$.timestamp").exists());
  }

  @Test
  void getGame_malformedUuid_returns400MalformedRequest() throws Exception {
    mockMvc
        .perform(get("/api/games/{id}", "not-a-uuid"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error", equalTo("MALFORMED_REQUEST")))
        .andExpect(jsonPath("$.message").exists())
        .andExpect(jsonPath("$.timestamp").exists());
  }

  @Test
  void getGame_existingGame_returns200WithState() throws Exception {
    GameSetup setup = createGame("Alice", "Bob");

    mockMvc
        .perform(get("/api/games/{id}", setup.gameId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id", equalTo(setup.gameId().toString())))
        .andExpect(jsonPath("$.white.id", equalTo(setup.whitePlayerId().toString())))
        .andExpect(jsonPath("$.white.displayName", equalTo("Alice")))
        .andExpect(jsonPath("$.black.id", equalTo(setup.blackPlayerId().toString())))
        .andExpect(jsonPath("$.black.displayName", equalTo("Bob")))
        .andExpect(jsonPath("$.status", equalTo("ONGOING")))
        .andExpect(jsonPath("$.turn", equalTo("WHITE")))
        .andExpect(jsonPath("$.moves", empty()));
  }

  @Test
  void getGame_untimedGame_clockFieldsAreNull() throws Exception {
    GameSetup setup = createGame("Alice", "Bob");

    mockMvc
        .perform(get("/api/games/{id}", setup.gameId()))
        .andExpect(status().isOk())
        // Feature 22 regression: an untimed room produces null clock fields everywhere; the
        // response shape is otherwise unchanged.
        .andExpect(jsonPath("$.whiteTimeRemainingMs").doesNotExist())
        .andExpect(jsonPath("$.blackTimeRemainingMs").doesNotExist())
        .andExpect(jsonPath("$.lastMoveAt").doesNotExist());
  }

  @Test
  void getGame_timedGame_clockInitialisedToInitialMs() throws Exception {
    GameSetup setup = createTimedGame("Alice", "Bob", 600_000L, 3_000L);

    mockMvc
        .perform(get("/api/games/{id}", setup.gameId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.whiteTimeRemainingMs", equalTo(600_000)))
        .andExpect(jsonPath("$.blackTimeRemainingMs", equalTo(600_000)))
        .andExpect(jsonPath("$.lastMoveAt").exists());
  }

  @Test
  void moveOnTimedGame_decrementsMoverAndAppliesIncrement() throws Exception {
    GameSetup setup = createTimedGame("Alice", "Bob", 600_000L, 3_000L);

    applyMove(setup.gameId(), setup.whitePlayerId(), "e2", "e4").andExpect(status().isOk());

    MvcResult result =
        mockMvc
            .perform(get("/api/games/{id}", setup.gameId()))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
    long white = body.get("whiteTimeRemainingMs").asLong();
    long black = body.get("blackTimeRemainingMs").asLong();
    // White moved: decremented by the (small) elapsed and then + 3000 increment. Bounded above by
    // initial + increment; strictly below initial would only hold if elapsed > increment, which a
    // fast test cannot guarantee — so assert the inclusive upper bound and that black is untouched.
    assertThat(white).isLessThanOrEqualTo(603_000L);
    assertThat(black).isEqualTo(600_000L);
  }

  @Test
  void moveSequence_foolsMate_returns200AndCheckmateStatus() throws Exception {
    // Fool's Mate, the shortest possible checkmate in chess:
    //   1. f2-f3  (White's weakening pawn push)
    //      e7-e5  (Black opens the diagonal towards h4)
    //   2. g2-g4  (White further weakens the king's diagonal)
    //      d8-h4# (Black's queen delivers mate on h4; White has no legal response)
    GameSetup setup = createGame("Alice", "Bob");

    applyMove(setup.gameId(), setup.whitePlayerId(), "f2", "f3").andExpect(status().isOk());
    applyMove(setup.gameId(), setup.blackPlayerId(), "e7", "e5").andExpect(status().isOk());
    applyMove(setup.gameId(), setup.whitePlayerId(), "g2", "g4").andExpect(status().isOk());

    applyMove(setup.gameId(), setup.blackPlayerId(), "d8", "h4")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status", equalTo("CHECKMATE")))
        // chesslib reports CHECKMATE regardless of which side delivered it; the `turn` field
        // still reflects whose turn it would be next (white, who is in fact mated here).
        .andExpect(jsonPath("$.turn", equalTo("WHITE")))
        .andExpect(jsonPath("$.moves", hasSize(4)));
  }

  @Test
  @Transactional
  void moveSequence_foolsMate_archivesGameToPostgres() throws Exception {
    // Same Fool's Mate flow as above, but here we follow the REST mutation with a direct read
    // of the Postgres archive. The archive call happens inside GameService.applyMove right before
    // GameStore.compute commits, so reaching CHECKMATE on the REST side must produce exactly one
    // row in the games table for this game id. @Transactional keeps the JPA session open across
    // the call so the lazy `moves` collection is reachable for the size assertion.
    GameSetup setup = createGame("Alice", "Bob");

    applyMove(setup.gameId(), setup.whitePlayerId(), "f2", "f3").andExpect(status().isOk());
    applyMove(setup.gameId(), setup.blackPlayerId(), "e7", "e5").andExpect(status().isOk());
    applyMove(setup.gameId(), setup.whitePlayerId(), "g2", "g4").andExpect(status().isOk());
    applyMove(setup.gameId(), setup.blackPlayerId(), "d8", "h4")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status", equalTo("CHECKMATE")));

    GameEntity archived = gameHistoryRepository.findById(setup.gameId()).orElseThrow();
    assertThat(archived.getStatus()).isEqualTo(GameStatus.CHECKMATE);
    assertThat(archived.getMoves()).hasSize(4);
    assertThat(archived.getWhitePlayerId()).isEqualTo(setup.whitePlayerId());
    assertThat(archived.getBlackPlayerId()).isEqualTo(setup.blackPlayerId());
  }

  @Test
  void moveByWrongPlayer_returns422NotYourTurn() throws Exception {
    GameSetup setup = createGame("Alice", "Bob");

    applyMove(setup.gameId(), setup.blackPlayerId(), "e7", "e5")
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.error", equalTo("NOT_YOUR_TURN")))
        .andExpect(jsonPath("$.message").exists())
        .andExpect(jsonPath("$.timestamp").exists());
  }

  @Test
  void illegalMove_returns422IllegalMove() throws Exception {
    // White pawn jumping three squares (e2-e5) is structurally valid (passes @Pattern + Square +
    // Move constructors) but illegal in chess; chesslib's legalMoves does not contain it.
    GameSetup setup = createGame("Alice", "Bob");

    applyMove(setup.gameId(), setup.whitePlayerId(), "e2", "e5")
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.error", equalTo("ILLEGAL_MOVE")))
        .andExpect(jsonPath("$.message").exists())
        .andExpect(jsonPath("$.timestamp").exists());
  }

  @Test
  void moveAfterCheckmate_returns409GameAlreadyEnded() throws Exception {
    // Play Fool's Mate to completion, then attempt one more move.
    GameSetup setup = createGame("Alice", "Bob");

    applyMove(setup.gameId(), setup.whitePlayerId(), "f2", "f3").andExpect(status().isOk());
    applyMove(setup.gameId(), setup.blackPlayerId(), "e7", "e5").andExpect(status().isOk());
    applyMove(setup.gameId(), setup.whitePlayerId(), "g2", "g4").andExpect(status().isOk());
    applyMove(setup.gameId(), setup.blackPlayerId(), "d8", "h4").andExpect(status().isOk());

    applyMove(setup.gameId(), setup.whitePlayerId(), "a2", "a3")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error", equalTo("GAME_ALREADY_ENDED")))
        .andExpect(jsonPath("$.message").exists())
        .andExpect(jsonPath("$.timestamp").exists());
  }

  @Test
  void moveWithoutPlayerIdHeader_returns400MissingHeader() throws Exception {
    GameSetup setup = createGame("Alice", "Bob");

    mockMvc
        .perform(
            post("/api/games/{id}/moves", setup.gameId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"from\":\"e2\",\"to\":\"e4\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error", equalTo("MISSING_HEADER")))
        .andExpect(jsonPath("$.message", containsString("X-Player-Id")))
        .andExpect(jsonPath("$.timestamp").exists());
  }

  private ResultActions applyMove(UUID gameId, UUID playerId, String from, String to)
      throws Exception {
    return mockMvc.perform(
        post("/api/games/{id}/moves", gameId)
            .contentType(MediaType.APPLICATION_JSON)
            .header("X-Player-Id", playerId.toString())
            .content("{\"from\":\"" + from + "\",\"to\":\"" + to + "\"}"));
  }

  /**
   * Creates a room as {@code whiteName} and joins it as {@code blackName}, returning the gameId and
   * both player ids in a single record.
   */
  private GameSetup createGame(String whiteName, String blackName) throws Exception {
    MvcResult createResult =
        mockMvc
            .perform(
                post("/api/rooms")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"displayName\":\"" + whiteName + "\"}"))
            .andExpect(status().isCreated())
            .andReturn();
    JsonNode createBody = objectMapper.readTree(createResult.getResponse().getContentAsString());
    String roomId = createBody.get("roomId").asText();
    String joinToken = createBody.get("joinToken").asText();
    UUID whitePlayerId = UUID.fromString(createBody.get("playerId").asText());

    MvcResult joinResult =
        mockMvc
            .perform(
                post("/api/rooms/{id}/join", roomId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"displayName\":\""
                            + blackName
                            + "\",\"joinToken\":\""
                            + joinToken
                            + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode joinBody = objectMapper.readTree(joinResult.getResponse().getContentAsString());
    UUID gameId = UUID.fromString(joinBody.get("gameId").asText());
    UUID blackPlayerId = UUID.fromString(joinBody.get("playerId").asText());

    return new GameSetup(gameId, whitePlayerId, blackPlayerId);
  }

  /**
   * Creates a timed room (declared {@code timeControl}) as {@code whiteName} and joins it as {@code
   * blackName}, returning the gameId and both player ids.
   */
  private GameSetup createTimedGame(
      String whiteName, String blackName, long initialMs, long incrementMs) throws Exception {
    String createBody =
        "{\"displayName\":\""
            + whiteName
            + "\",\"timeControl\":{\"initialMs\":"
            + initialMs
            + ",\"incrementMs\":"
            + incrementMs
            + "}}";
    MvcResult createResult =
        mockMvc
            .perform(post("/api/rooms").contentType(MediaType.APPLICATION_JSON).content(createBody))
            .andExpect(status().isCreated())
            .andReturn();
    JsonNode create = objectMapper.readTree(createResult.getResponse().getContentAsString());
    String roomId = create.get("roomId").asText();
    String joinToken = create.get("joinToken").asText();
    UUID whitePlayerId = UUID.fromString(create.get("playerId").asText());

    MvcResult joinResult =
        mockMvc
            .perform(
                post("/api/rooms/{id}/join", roomId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"displayName\":\""
                            + blackName
                            + "\",\"joinToken\":\""
                            + joinToken
                            + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode join = objectMapper.readTree(joinResult.getResponse().getContentAsString());
    UUID gameId = UUID.fromString(join.get("gameId").asText());
    UUID blackPlayerId = UUID.fromString(join.get("playerId").asText());

    return new GameSetup(gameId, whitePlayerId, blackPlayerId);
  }

  /** Tuple returned by {@link #createGame(String, String)}. */
  private record GameSetup(UUID gameId, UUID whitePlayerId, UUID blackPlayerId) {}
}
