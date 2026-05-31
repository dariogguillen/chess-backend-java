package io.github.dariogguillen.chess.service.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.github.bhlangonijr.chesslib.Board;
import io.github.dariogguillen.chess.config.BotProperties;
import io.github.dariogguillen.chess.domain.Move;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * <strong>Gated</strong> integration test for the real {@link StockfishBotEngine} subprocess
 * adapter (feature 23, `bot-opponent`). Unlike every other bot test, this one exercises the actual
 * Stockfish binary over UCI — so it is guarded by {@code assumeTrue}: when Stockfish is not found
 * on {@code PATH} (or at the configured default path), the test is <em>skipped</em>, not failed.
 * This is what lets {@code ./init.sh} stay green on a machine / CI runner without Stockfish
 * installed while still shipping real-engine coverage for anyone who has it (and for the Docker
 * image, which bundles it).
 *
 * <p>The engine is constructed directly (no Spring context) so the test stays fast and independent
 * of Testcontainers. The {@link BotProperties} point at the resolved binary path with a generous
 * timeout.
 *
 * <p>Feature 23.5 (`bot-difficulty`) widened {@code chooseMove} to take a target Elo and made the
 * adapter issue {@code UCI_LimitStrength} / {@code UCI_Elo} before the search. This test passes an
 * in-range Elo and asserts the option exchange still yields a legal move — it does not assert a
 * precise playing strength (Elo-limited play is not deterministic), only that the option wiring
 * does not break move selection.
 */
class StockfishEngineIT {

  private static final String INITIAL_FEN =
      "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
  private static final int TARGET_ELO = 1500;

  @Test
  void chooseMove_fromInitialPosition_returnsALegalMove() {
    String enginePath = resolveStockfishPath();
    assumeTrue(enginePath != null, "Stockfish binary not found on PATH or default path; skipping");

    StockfishBotEngine engine =
        new StockfishBotEngine(
            new BotProperties(
                enginePath, Duration.ofMillis(500), Duration.ofSeconds(10), 1, TARGET_ELO));

    Move move = engine.chooseMove(INITIAL_FEN, TARGET_ELO);

    assertThat(move).isNotNull();
    assertThat(isLegalInInitialPosition(move)).isTrue();
  }

  /**
   * Resolves a runnable Stockfish binary: first the {@code chess.bot.engine-path} production
   * default ({@code /usr/games/stockfish}), then each {@code PATH} entry. Returns {@code null} when
   * none is executable, which the caller turns into a skip.
   */
  private static String resolveStockfishPath() {
    Path defaultPath = Paths.get("/usr/games/stockfish");
    if (Files.isExecutable(defaultPath)) {
      return defaultPath.toString();
    }
    String pathEnv = System.getenv("PATH");
    if (pathEnv == null) {
      return null;
    }
    return Arrays.stream(pathEnv.split(File.pathSeparator))
        .map(dir -> Paths.get(dir, "stockfish"))
        .filter(Files::isExecutable)
        .map(Path::toString)
        .findFirst()
        .orElse(null);
  }

  /** Replays the engine's move on a fresh board to confirm it is legal in the initial position. */
  private static boolean isLegalInInitialPosition(Move move) {
    Board board = new Board();
    board.loadFromFen(INITIAL_FEN);
    String promo = move.promotion().map(p -> p.name().substring(0, 1)).orElse("");
    String uci = move.from().value() + move.to().value() + promo.toLowerCase();
    return board.legalMoves().stream().anyMatch(legal -> legal.toString().equalsIgnoreCase(uci));
  }
}
