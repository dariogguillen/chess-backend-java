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
 * <strong>Gated</strong> integration test for the real {@link FairyStockfishBotEngine} subprocess
 * adapter (feature 23, {@code bot-opponent}; strength model reworked in feature 23.7, {@code
 * bot-strength-fairy-stockfish}). Unlike every other bot test, this one exercises the actual
 * Fairy-Stockfish binary over UCI — so it is guarded by {@code assumeTrue}: when the binary is not
 * found on {@code PATH} (or at the configured default path), the test is <em>skipped</em>, not
 * failed. This is what lets {@code ./init.sh} stay green on a machine / CI runner without the
 * engine installed while still shipping real-engine coverage for anyone who has it (and for the
 * Docker image, which bundles it).
 *
 * <p>The engine is constructed directly (no Spring context) so the test stays fast and independent
 * of Testcontainers. The {@link BotProperties} point at the resolved binary path with a generous
 * timeout.
 *
 * <p>Feature 23.7 replaced 23.5's {@code UCI_LimitStrength}/{@code UCI_Elo} with Fairy-Stockfish's
 * {@code Skill Level} + {@code go depth} model. These tests pass a low Elo and a high Elo and
 * assert the {@code (Skill Level, depth)} option exchange still yields a legal move at each end —
 * they do not assert a precise playing strength (Skill-Level play is randomized and not
 * deterministic), only that the option wiring does not break move selection across the widened
 * range.
 */
class FairyStockfishEngineIT {

  private static final String INITIAL_FEN =
      "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
  private static final int LOW_ELO = 400;
  private static final int HIGH_ELO = 3190;

  @Test
  void chooseMove_atLowElo_returnsALegalMove() {
    Move move = chooseFromInitialPosition(LOW_ELO);

    assertThat(move).isNotNull();
    assertThat(isLegalInInitialPosition(move)).isTrue();
  }

  @Test
  void chooseMove_atHighElo_returnsALegalMove() {
    Move move = chooseFromInitialPosition(HIGH_ELO);

    assertThat(move).isNotNull();
    assertThat(isLegalInInitialPosition(move)).isTrue();
  }

  /**
   * Resolves the binary, skips the test when absent, and runs one search at {@code elo} from the
   * initial position. The high-Elo case runs a {@code go depth 22}, hence the generous 30s timeout.
   */
  private static Move chooseFromInitialPosition(int elo) {
    String enginePath = resolveEnginePath();
    assumeTrue(
        enginePath != null, "Fairy-Stockfish binary not found on PATH or default path; skipping");

    FairyStockfishBotEngine engine =
        new FairyStockfishBotEngine(new BotProperties(enginePath, Duration.ofSeconds(30), 1, 1500));

    return engine.chooseMove(INITIAL_FEN, elo);
  }

  /**
   * Resolves a runnable Fairy-Stockfish binary: first the {@code chess.bot.engine-path} production
   * default ({@code /usr/local/bin/fairy-stockfish}), then each {@code PATH} entry under the common
   * names {@code fairy-stockfish} / {@code fairy-stockfish_x86-64}. Returns {@code null} when none
   * is executable, which the caller turns into a skip.
   */
  private static String resolveEnginePath() {
    Path defaultPath = Paths.get("/usr/local/bin/fairy-stockfish");
    if (Files.isExecutable(defaultPath)) {
      return defaultPath.toString();
    }
    String pathEnv = System.getenv("PATH");
    if (pathEnv == null) {
      return null;
    }
    return Arrays.stream(pathEnv.split(File.pathSeparator))
        .flatMap(
            dir ->
                Arrays.stream(new String[] {"fairy-stockfish", "fairy-stockfish_x86-64"})
                    .map(name -> Paths.get(dir, name)))
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
