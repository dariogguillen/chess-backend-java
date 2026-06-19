package io.github.dariogguillen.chess.service.bot;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dariogguillen.chess.config.BotProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit test for the UCI command construction of {@link FairyStockfishBotEngine} (feature 23.7,
 * {@code bot-strength-fairy-stockfish}).
 *
 * <p>It exercises the {@code buildSearchCommands} seam — the package-private method that produces
 * the exact UCI lines the adapter would write to the subprocess — so the new strength model
 * (Lichess's {@code Skill Level} + {@code go depth}, replacing 23.5's {@code
 * UCI_LimitStrength}/{@code UCI_Elo}) is proven <em>without</em> spawning the real binary. This is
 * what keeps {@code ./init.sh} binary-free while still locking the option wiring; the gated {@code
 * FairyStockfishEngineIT} covers the real subprocess when a binary is present.
 */
class FairyStockfishBotEngineTest {

  private static final String FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

  private final FairyStockfishBotEngine engine =
      new FairyStockfishBotEngine(
          new BotProperties("/usr/local/bin/fairy-stockfish", Duration.ofSeconds(30), 1, 1500));

  @Test
  void buildSearchCommands_emitsSkillLevelAndGoDepthForTheMappedTier() {
    // 1500 maps to tier 5: skill 7, depth 5 (see BotEloMapping).
    List<String> commands = engine.buildSearchCommands(FEN, 1500);

    assertThat(commands)
        .containsSubsequence(
            "isready", "setoption name Skill Level value 7", "position fen " + FEN, "go depth 5");
  }

  @Test
  void buildSearchCommands_disablesNnueForClassicalEval() {
    List<String> commands = engine.buildSearchCommands(FEN, 1500);

    assertThat(commands).contains("setoption name Use NNUE value false");
  }

  @Test
  void buildSearchCommands_atFloor_emitsNegativeSkillLevel() {
    // 400 is the weakest tier: skill -9, depth 5 — the negative level official Stockfish cannot do.
    List<String> commands = engine.buildSearchCommands(FEN, 400);

    assertThat(commands).contains("setoption name Skill Level value -9", "go depth 5");
  }

  @Test
  void buildSearchCommands_atCeiling_emitsFullStrengthSkillAndTopDepth() {
    List<String> commands = engine.buildSearchCommands(FEN, 3190);

    assertThat(commands).contains("setoption name Skill Level value 20", "go depth 22");
  }

  @Test
  void buildSearchCommands_neverEmitsTheOldUciEloOptions() {
    // The 23.5 model is fully replaced: no UCI_LimitStrength / UCI_Elo / go movetime survive.
    List<String> commands = engine.buildSearchCommands(FEN, 1500);

    assertThat(commands)
        .noneMatch(c -> c.contains("UCI_Elo"))
        .noneMatch(c -> c.contains("UCI_LimitStrength"))
        .noneMatch(c -> c.startsWith("go movetime"));
  }
}
