package io.github.dariogguillen.chess.web.me;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dariogguillen.chess.TestcontainersConfiguration;
import io.github.dariogguillen.chess.domain.Game;
import io.github.dariogguillen.chess.domain.GameResult;
import io.github.dariogguillen.chess.domain.GameStatus;
import io.github.dariogguillen.chess.domain.Move;
import io.github.dariogguillen.chess.domain.Piece;
import io.github.dariogguillen.chess.domain.Player;
import io.github.dariogguillen.chess.domain.Square;
import io.github.dariogguillen.chess.persistence.GameHistoryRepository;
import io.github.dariogguillen.chess.persistence.UserRepository;
import io.github.dariogguillen.chess.service.GameHistoryService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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

/**
 * Integration tests for {@code GET /api/me/games/{id}} (feature 23.94, {@code game-review}). Boots
 * the full Spring context with Testcontainers Postgres + Redis so the JWT filter chain, the JPA
 * archive (including the {@code LEFT JOIN FETCH g.moves} read path), and the controller projection
 * all participate end-to-end.
 *
 * <p>Each case wipes the {@code users} and {@code games} tables in {@link #cleanState()} so they
 * are independent. The cases mirror the verification list in {@code progress/current.md} under
 * "Feature 23.94 — Verification".
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class GameReviewIT {

  private static final String STARTING_FEN =
      "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
  private static final String FINAL_FEN =
      "rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq - 1 3";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserRepository users;
  @Autowired private GameHistoryRepository games;
  @Autowired private GameHistoryService gameHistoryService;

  @BeforeEach
  void cleanState() {
    games.deleteAll();
    users.deleteAll();
  }

  @AfterEach
  void cleanUpGames() {
    games.deleteAll();
  }

  @Test
  void getMyGame_withoutAuth_returns401() throws Exception {
    mockMvc
        .perform(get("/api/me/games/{id}", UUID.randomUUID()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error", equalTo("AUTHENTICATION_REQUIRED")))
        .andExpect(jsonPath("$.message").exists())
        .andExpect(jsonPath("$.timestamp").exists());
  }

  @Test
  void getMyGame_callerPlayedAsWhite_returnsFullOrderedMoveList() throws Exception {
    RegisteredUser alice = register("alice@example.com", "supersecret", "Alice");

    // Alice (white) vs a guest (black). Two moves, the second a promotion, so the move DTO carries
    // a populated promotion where the seeded game has one.
    Move e2e4 = new Move(new Square("e2"), new Square("e4"), Optional.<Piece>empty());
    Move promotion = new Move(new Square("a7"), new Square("a8"), Optional.of(Piece.QUEEN));
    Game game =
        new Game(
                UUID.randomUUID(),
                "ROOM01",
                new Player(UUID.randomUUID(), "Alice", alice.id()),
                new Player(UUID.randomUUID(), "Guest", null),
                STARTING_FEN,
                FINAL_FEN,
                GameStatus.CHECKMATE,
                List.of(e2e4, promotion))
            .withResult(GameResult.WHITE_WIN);
    gameHistoryService.archive(game);

    MvcResult result =
        mockMvc
            .perform(
                get("/api/me/games/{id}", game.id())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice.token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.gameId", equalTo(game.id().toString())))
            .andExpect(jsonPath("$.roomId", equalTo("ROOM01")))
            .andExpect(jsonPath("$.whiteDisplayName", equalTo("Alice")))
            .andExpect(jsonPath("$.blackDisplayName", equalTo("Guest")))
            .andExpect(jsonPath("$.selfSide", equalTo("WHITE")))
            .andExpect(jsonPath("$.status", equalTo("CHECKMATE")))
            .andExpect(jsonPath("$.result", equalTo("WHITE_WIN")))
            .andExpect(jsonPath("$.startingFen", equalTo(STARTING_FEN)))
            .andExpect(jsonPath("$.finalFen", equalTo(FINAL_FEN)))
            .andExpect(jsonPath("$.endedAt").exists())
            .andExpect(jsonPath("$.moves", hasSize(2)))
            .andReturn();

    JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
    JsonNode moves = body.get("moves");
    // Moves come back in move_idx order: e2-e4 first (no promotion), a7-a8=Q second.
    assertThat(moves.get(0).get("from").asText()).isEqualTo("e2");
    assertThat(moves.get(0).get("to").asText()).isEqualTo("e4");
    assertThat(moves.get(0).get("promotion").isNull()).isTrue();
    assertThat(moves.get(1).get("from").asText()).isEqualTo("a7");
    assertThat(moves.get(1).get("to").asText()).isEqualTo("a8");
    assertThat(moves.get(1).get("promotion").asText()).isEqualTo("QUEEN");
  }

  @Test
  void getMyGame_callerPlayedAsBlack_selfSideIsBlack() throws Exception {
    RegisteredUser alice = register("alice@example.com", "supersecret", "Alice");

    Game game =
        newTerminalGame(GameStatus.STALEMATE, null, "Guest", alice.id(), "Alice")
            .withResult(GameResult.DRAW);
    gameHistoryService.archive(game);

    mockMvc
        .perform(
            get("/api/me/games/{id}", game.id())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.gameId", equalTo(game.id().toString())))
        .andExpect(jsonPath("$.selfSide", equalTo("BLACK")))
        .andExpect(jsonPath("$.whiteDisplayName", equalTo("Guest")))
        .andExpect(jsonPath("$.blackDisplayName", equalTo("Alice")))
        .andExpect(jsonPath("$.result", equalTo("DRAW")))
        .andExpect(jsonPath("$.moves", hasSize(1)));
  }

  @Test
  void getMyGame_zeroMoveGame_returns200WithEmptyMoves() throws Exception {
    RegisteredUser alice = register("alice@example.com", "supersecret", "Alice");

    // A terminal game that ended before any move was played (e.g. a timeout). This is the case that
    // proves the LEFT (not inner) JOIN FETCH: an inner join would drop a game with zero move rows
    // and 404 here.
    Game game =
        new Game(
            UUID.randomUUID(),
            "ROOM02",
            new Player(UUID.randomUUID(), "Alice", alice.id()),
            new Player(UUID.randomUUID(), "Guest", null),
            STARTING_FEN,
            STARTING_FEN,
            GameStatus.TIMEOUT,
            List.of());
    gameHistoryService.archive(game);

    mockMvc
        .perform(
            get("/api/me/games/{id}", game.id())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.gameId", equalTo(game.id().toString())))
        .andExpect(jsonPath("$.selfSide", equalTo("WHITE")))
        .andExpect(jsonPath("$.status", equalTo("TIMEOUT")))
        .andExpect(jsonPath("$.moves", hasSize(0)));
  }

  @Test
  void getMyGame_callerNotAParticipant_returns404() throws Exception {
    RegisteredUser alice = register("alice@example.com", "supersecret", "Alice");
    RegisteredUser bob = register("bob@example.com", "supersecret", "Bob");

    // Bob's game; Alice was not a participant.
    Game bobGame = newTerminalGame(GameStatus.CHECKMATE, bob.id(), "Bob", null, "Guest");
    gameHistoryService.archive(bobGame);

    mockMvc
        .perform(
            get("/api/me/games/{id}", bobGame.id())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice.token()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error", equalTo("GAME_NOT_FOUND")));
  }

  @Test
  void getMyGame_unknownId_returns404() throws Exception {
    RegisteredUser alice = register("alice@example.com", "supersecret", "Alice");

    mockMvc
        .perform(
            get("/api/me/games/{id}", UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice.token()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error", equalTo("GAME_NOT_FOUND")));
  }

  @Test
  void getMyGame_anonymousGame_returns404() throws Exception {
    RegisteredUser alice = register("alice@example.com", "supersecret", "Alice");

    // Anonymous game (both user ids null) — no authenticated caller is a participant.
    Game anonGame = newTerminalGame(GameStatus.CHECKMATE, null, "Guest1", null, "Guest2");
    gameHistoryService.archive(anonGame);

    mockMvc
        .perform(
            get("/api/me/games/{id}", anonGame.id())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice.token()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error", equalTo("GAME_NOT_FOUND")));
  }

  private RegisteredUser register(String email, String password, String displayName)
      throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        String.format(
                            "{\"email\":\"%s\",\"password\":\"%s\",\"displayName\":\"%s\"}",
                            email, password, displayName)))
            .andExpect(status().isCreated())
            .andReturn();
    JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
    return new RegisteredUser(
        UUID.fromString(body.get("user").get("id").asText()), body.get("token").asText());
  }

  private static Game newTerminalGame(
      GameStatus status, UUID whiteUserId, String whiteName, UUID blackUserId, String blackName) {
    Player white = new Player(UUID.randomUUID(), whiteName, whiteUserId);
    Player black = new Player(UUID.randomUUID(), blackName, blackUserId);
    Move move = new Move(new Square("e2"), new Square("e4"), Optional.<Piece>empty());
    return new Game(
        UUID.randomUUID(), "ROOM01", white, black, STARTING_FEN, FINAL_FEN, status, List.of(move));
  }

  private record RegisteredUser(UUID id, String token) {}
}
