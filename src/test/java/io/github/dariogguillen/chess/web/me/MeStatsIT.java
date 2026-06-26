package io.github.dariogguillen.chess.web.me;

import static org.hamcrest.Matchers.equalTo;
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
 * Integration tests for {@code GET /api/me/stats} (feature 23.93, {@code me-stats}). Boots the full
 * Spring context with Testcontainers Postgres + Redis so the JWT filter chain, the JPA archive, and
 * the single-row JPQL aggregate all participate end-to-end.
 *
 * <p>Each case wipes {@code games} then {@code users} in {@link #cleanState()} so cases are
 * independent (child rows before parent rows, mirroring the FK ordering {@code MyGamesIT} relies
 * on). Games are seeded directly through {@link GameHistoryService#archive(Game)} with an explicit
 * {@link GameResult} (terminal games carry a result since feature 23.92), and a NULL-result game
 * for the {@code unknown} bucket.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class MeStatsIT {

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
  void getMyStats_withoutAuth_returns401() throws Exception {
    mockMvc
        .perform(get("/api/me/stats"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error", equalTo("AUTHENTICATION_REQUIRED")))
        .andExpect(jsonPath("$.message").exists())
        .andExpect(jsonPath("$.timestamp").exists());
  }

  @Test
  void getMyStats_emptyHistory_returnsAllZeroAndZeroWinRate() throws Exception {
    RegisteredUser alice = register("alice@example.com", "supersecret", "Alice");

    mockMvc
        .perform(get("/api/me/stats").header(HttpHeaders.AUTHORIZATION, "Bearer " + alice.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total", equalTo(0)))
        .andExpect(jsonPath("$.wins", equalTo(0)))
        .andExpect(jsonPath("$.losses", equalTo(0)))
        .andExpect(jsonPath("$.draws", equalTo(0)))
        .andExpect(jsonPath("$.unknown", equalTo(0)))
        .andExpect(jsonPath("$.winRate", equalTo(0.0)));
  }

  @Test
  void getMyStats_winsAsEitherSide_bothCountAsWins() throws Exception {
    RegisteredUser alice = register("alice@example.com", "supersecret", "Alice");

    // Win as white: WHITE_WIN and Alice is white_user_id.
    gameHistoryService.archive(seed(GameStatus.CHECKMATE, GameResult.WHITE_WIN, alice.id(), null));
    // Win as black: BLACK_WIN and Alice is black_user_id.
    gameHistoryService.archive(seed(GameStatus.CHECKMATE, GameResult.BLACK_WIN, null, alice.id()));

    mockMvc
        .perform(get("/api/me/stats").header(HttpHeaders.AUTHORIZATION, "Bearer " + alice.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total", equalTo(2)))
        .andExpect(jsonPath("$.wins", equalTo(2)))
        .andExpect(jsonPath("$.losses", equalTo(0)))
        .andExpect(jsonPath("$.draws", equalTo(0)))
        .andExpect(jsonPath("$.unknown", equalTo(0)))
        .andExpect(jsonPath("$.winRate", equalTo(1.0)));
  }

  @Test
  void getMyStats_lossesAsEitherSide_bothCountAsLosses() throws Exception {
    RegisteredUser alice = register("alice@example.com", "supersecret", "Alice");

    // Loss as white: BLACK_WIN and Alice is white_user_id.
    gameHistoryService.archive(seed(GameStatus.CHECKMATE, GameResult.BLACK_WIN, alice.id(), null));
    // Loss as black: WHITE_WIN and Alice is black_user_id.
    gameHistoryService.archive(seed(GameStatus.CHECKMATE, GameResult.WHITE_WIN, null, alice.id()));

    mockMvc
        .perform(get("/api/me/stats").header(HttpHeaders.AUTHORIZATION, "Bearer " + alice.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total", equalTo(2)))
        .andExpect(jsonPath("$.wins", equalTo(0)))
        .andExpect(jsonPath("$.losses", equalTo(2)))
        .andExpect(jsonPath("$.draws", equalTo(0)))
        .andExpect(jsonPath("$.unknown", equalTo(0)))
        .andExpect(jsonPath("$.winRate", equalTo(0.0)));
  }

  @Test
  void getMyStats_draw_countsAsDraw() throws Exception {
    RegisteredUser alice = register("alice@example.com", "supersecret", "Alice");

    gameHistoryService.archive(seed(GameStatus.STALEMATE, GameResult.DRAW, alice.id(), null));

    mockMvc
        .perform(get("/api/me/stats").header(HttpHeaders.AUTHORIZATION, "Bearer " + alice.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total", equalTo(1)))
        .andExpect(jsonPath("$.wins", equalTo(0)))
        .andExpect(jsonPath("$.losses", equalTo(0)))
        .andExpect(jsonPath("$.draws", equalTo(1)))
        .andExpect(jsonPath("$.unknown", equalTo(0)))
        // A single draw is decided: winRate = 0 wins / 1 decided = 0.0.
        .andExpect(jsonPath("$.winRate", equalTo(0.0)));
  }

  @Test
  void getMyStats_nullResultRow_countsAsUnknownNotWinLossDraw() throws Exception {
    RegisteredUser alice = register("alice@example.com", "supersecret", "Alice");

    // Legacy ABANDONED row with no recoverable result.
    gameHistoryService.archive(seed(GameStatus.ABANDONED, null, alice.id(), null));

    mockMvc
        .perform(get("/api/me/stats").header(HttpHeaders.AUTHORIZATION, "Bearer " + alice.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total", equalTo(1)))
        .andExpect(jsonPath("$.wins", equalTo(0)))
        .andExpect(jsonPath("$.losses", equalTo(0)))
        .andExpect(jsonPath("$.draws", equalTo(0)))
        .andExpect(jsonPath("$.unknown", equalTo(1)))
        // No decided games -> winRate 0.0 (divide-by-zero guard).
        .andExpect(jsonPath("$.winRate", equalTo(0.0)));
  }

  @Test
  void getMyStats_otherUsersGames_notCounted() throws Exception {
    RegisteredUser alice = register("alice@example.com", "supersecret", "Alice");
    RegisteredUser bob = register("bob@example.com", "supersecret", "Bob");

    // Alice's single win.
    gameHistoryService.archive(seed(GameStatus.CHECKMATE, GameResult.WHITE_WIN, alice.id(), null));
    // Bob's games — must not leak into Alice's aggregate.
    gameHistoryService.archive(seed(GameStatus.CHECKMATE, GameResult.WHITE_WIN, bob.id(), null));
    gameHistoryService.archive(seed(GameStatus.CHECKMATE, GameResult.BLACK_WIN, bob.id(), null));

    mockMvc
        .perform(get("/api/me/stats").header(HttpHeaders.AUTHORIZATION, "Bearer " + alice.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total", equalTo(1)))
        .andExpect(jsonPath("$.wins", equalTo(1)))
        .andExpect(jsonPath("$.losses", equalTo(0)))
        .andExpect(jsonPath("$.draws", equalTo(0)))
        .andExpect(jsonPath("$.unknown", equalTo(0)))
        .andExpect(jsonPath("$.winRate", equalTo(1.0)));
  }

  @Test
  void getMyStats_mixedRecord_reconcilesAndComputesWinRate() throws Exception {
    RegisteredUser alice = register("alice@example.com", "supersecret", "Alice");

    // 2 wins, 1 loss, 1 draw, 1 unknown -> total 5, winRate = 2 / (2+1+1) = 0.5.
    gameHistoryService.archive(
        seed(GameStatus.CHECKMATE, GameResult.WHITE_WIN, alice.id(), null)); // win as white
    gameHistoryService.archive(
        seed(GameStatus.CHECKMATE, GameResult.BLACK_WIN, null, alice.id())); // win as black
    gameHistoryService.archive(
        seed(GameStatus.CHECKMATE, GameResult.BLACK_WIN, alice.id(), null)); // loss as white
    gameHistoryService.archive(
        seed(GameStatus.STALEMATE, GameResult.DRAW, null, alice.id())); // draw as black
    gameHistoryService.archive(seed(GameStatus.ABANDONED, null, alice.id(), null)); // unknown

    mockMvc
        .perform(get("/api/me/stats").header(HttpHeaders.AUTHORIZATION, "Bearer " + alice.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total", equalTo(5)))
        .andExpect(jsonPath("$.wins", equalTo(2)))
        .andExpect(jsonPath("$.losses", equalTo(1)))
        .andExpect(jsonPath("$.draws", equalTo(1)))
        .andExpect(jsonPath("$.unknown", equalTo(1)))
        .andExpect(jsonPath("$.winRate", equalTo(0.5)));
  }

  /**
   * Seeds one terminal game with the given status, result, and user-id sides. The opposite side is
   * a guest (null user id). The {@code result} may be null to model a legacy unknown-result row.
   */
  private static Game seed(
      GameStatus status, GameResult result, UUID whiteUserId, UUID blackUserId) {
    Player white = new Player(UUID.randomUUID(), "White", whiteUserId);
    Player black = new Player(UUID.randomUUID(), "Black", blackUserId);
    Move move = new Move(new Square("e2"), new Square("e4"), Optional.<Piece>empty());
    Game game =
        new Game(
            UUID.randomUUID(),
            "ROOM01",
            white,
            black,
            STARTING_FEN,
            FINAL_FEN,
            status,
            List.of(move));
    return result == null ? game : game.withResult(result);
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

  private record RegisteredUser(UUID id, String token) {}
}
