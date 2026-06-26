package io.github.dariogguillen.chess.web.game;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.dariogguillen.chess.TestcontainersConfiguration;
import io.github.dariogguillen.chess.domain.Game;
import io.github.dariogguillen.chess.domain.GameResult;
import io.github.dariogguillen.chess.domain.GameStatus;
import io.github.dariogguillen.chess.domain.Move;
import io.github.dariogguillen.chess.domain.Piece;
import io.github.dariogguillen.chess.domain.Player;
import io.github.dariogguillen.chess.domain.Square;
import io.github.dariogguillen.chess.service.GameHistoryService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Integration tests for {@code GET /api/players/{id}/games}. Boots the full Spring context against
 * Testcontainers Postgres, archives a couple of games via {@link GameHistoryService} (rather than
 * playing them through the REST surface — that path is covered by {@code GameControllerIT}'s Fool's
 * Mate extension), and asserts the controller projects the archive rows into the wire shape
 * correctly: ordering newest-first, the queried player's role on each game, the opponent's display
 * name, and the empty-list for an unknown id.
 *
 * <p>{@code playerId} and {@code gameId} flow as {@link UUID} through the controller signature; the
 * tests construct random UUIDs for the inputs and compare against {@code toString()}-rendered
 * values in the JSON assertions.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class PlayerGamesControllerIT {

  private static final String STARTING_FEN =
      "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
  private static final String FINAL_FEN =
      "rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq - 1 3";

  @Autowired private MockMvc mockMvc;

  @Autowired private GameHistoryService gameHistoryService;

  @Test
  void getPlayerGames_unknownPlayer_returns200WithEmptyArray() throws Exception {
    UUID unknownId = UUID.randomUUID();

    mockMvc
        .perform(get("/api/players/{id}/games", unknownId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", empty()));
  }

  @Test
  void getPlayerGames_malformedUuid_returns400MalformedRequest() throws Exception {
    mockMvc
        .perform(get("/api/players/{id}/games", "not-a-uuid"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error", equalTo("MALFORMED_REQUEST")))
        .andExpect(jsonPath("$.message").exists())
        .andExpect(jsonPath("$.timestamp").exists());
  }

  @Test
  void getPlayerGames_playerHasGames_returnsSummariesNewestFirst() throws Exception {
    UUID aliceId = UUID.randomUUID();
    UUID bobId = UUID.randomUUID();
    UUID carolId = UUID.randomUUID();

    // Game A: archived first, Alice is WHITE, opponent Bob.
    Game gameA =
        newTerminalGame(GameStatus.CHECKMATE, aliceId, "Alice", bobId, "Bob")
            .withResult(GameResult.WHITE_WIN);
    gameHistoryService.archive(gameA);

    // Sleep a millisecond so the `ended_at` ordering is strict, even on hosts with low-resolution
    // clocks. The mapper sources `endedAt` from the injected Clock (which Spring autowires as
    // Clock.systemUTC()), so two archives in the same nanosecond would be ordered arbitrarily.
    Thread.sleep(10);

    // Game B: archived second, Alice is BLACK, opponent Carol.
    Game gameB =
        newTerminalGame(GameStatus.STALEMATE, carolId, "Carol", aliceId, "Alice")
            .withResult(GameResult.DRAW);
    gameHistoryService.archive(gameB);

    mockMvc
        .perform(get("/api/players/{id}/games", aliceId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(2)))
        // Newest first — Game B came second, so it leads.
        .andExpect(jsonPath("$[0].gameId", equalTo(gameB.id().toString())))
        .andExpect(jsonPath("$[0].selfRole", equalTo("BLACK")))
        .andExpect(jsonPath("$[0].opponentDisplayName", equalTo("Carol")))
        .andExpect(jsonPath("$[0].status", equalTo("STALEMATE")))
        .andExpect(jsonPath("$[0].result", equalTo("DRAW")))
        .andExpect(jsonPath("$[0].moveCount", equalTo(1)))
        .andExpect(jsonPath("$[1].gameId", equalTo(gameA.id().toString())))
        .andExpect(jsonPath("$[1].selfRole", equalTo("WHITE")))
        .andExpect(jsonPath("$[1].opponentDisplayName", equalTo("Bob")))
        .andExpect(jsonPath("$[1].status", equalTo("CHECKMATE")))
        .andExpect(jsonPath("$[1].result", equalTo("WHITE_WIN")))
        .andExpect(jsonPath("$[1].moveCount", equalTo(1)));
  }

  private static Game newTerminalGame(
      GameStatus status, UUID whiteId, String whiteName, UUID blackId, String blackName) {
    Player white = new Player(whiteId, whiteName);
    Player black = new Player(blackId, blackName);
    Move move = new Move(new Square("e2"), new Square("e4"), Optional.<Piece>empty());
    return new Game(
        UUID.randomUUID(), "ROOM01", white, black, STARTING_FEN, FINAL_FEN, status, List.of(move));
  }
}
