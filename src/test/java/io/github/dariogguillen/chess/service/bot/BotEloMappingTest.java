package io.github.dariogguillen.chess.service.bot;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Unit tests for {@link BotEloMapping} — the pure Elo→{@code (Skill Level, depth)} table that ports
 * Lichess's strength model (feature 23.7, {@code bot-strength-fairy-stockfish}).
 *
 * <p>This is exactly the kind of branchy, pure logic the conventions say earns a unit test: it is
 * the single source of truth for bot strength, exercises interpolation arithmetic the ITs cannot
 * reasonably reach, and needs no Spring context. The anchors are asserted verbatim against lila's
 * fishnet {@code SkillLevel} table, the interpolation is checked between anchors, and the floor /
 * ceiling clamping is pinned.
 */
class BotEloMappingTest {

  /**
   * The eight Lichess anchor tiers map to their published {@code (skill, depth)} verbatim. These
   * are the lila/fishnet values; tier 7 is skill 16 (not the 15 some forum summaries cite).
   */
  @ParameterizedTest
  @CsvSource({
    "400,  -9, 5",
    "500,  -5, 5",
    "800,  -1, 5",
    "1100,  3, 5",
    "1500,  7, 5",
    "1900, 11, 8",
    "2300, 16, 13",
    "3190, 20, 22",
  })
  void forElo_atAnchorTier_returnsThatTiersSkillAndDepth(int elo, int skill, int depth) {
    EngineStrength strength = BotEloMapping.forElo(elo);

    assertThat(strength.skillLevel()).isEqualTo(skill);
    assertThat(strength.depth()).isEqualTo(depth);
  }

  @Test
  void forElo_atFloor_returnsTheMostNegativeSkillAndShallowestDepth() {
    EngineStrength strength = BotEloMapping.forElo(BotEngine.MIN_BOT_ELO);

    assertThat(strength.skillLevel()).isEqualTo(-9);
    assertThat(strength.depth()).isEqualTo(5);
  }

  @Test
  void forElo_belowFloor_clampsToTheWeakestTier() {
    // The DTO @Min already rejects sub-400 at the boundary, but the mapping must be total and safe.
    EngineStrength strength = BotEloMapping.forElo(100);

    assertThat(strength.skillLevel()).isEqualTo(-9);
    assertThat(strength.depth()).isEqualTo(5);
  }

  @Test
  void forElo_atCeiling_returnsFullStrengthSkillAndTopDepth() {
    EngineStrength strength = BotEloMapping.forElo(BotEngine.MAX_BOT_ELO);

    assertThat(strength.skillLevel()).isEqualTo(20);
    assertThat(strength.depth()).isEqualTo(22);
  }

  @Test
  void forElo_aboveCeiling_clampsToFullStrength() {
    EngineStrength strength = BotEloMapping.forElo(9999);

    assertThat(strength.skillLevel()).isEqualTo(20);
    assertThat(strength.depth()).isEqualTo(22);
  }

  @Test
  void forElo_midwayBetweenTwoDepth5Anchors_interpolatesSkillAndKeepsDepth5() {
    // Halfway between tier 4 (1100, skill 3, depth 5) and tier 5 (1500, skill 7, depth 5).
    // skill = round(3 + (7-3)*0.5) = 5; depth stays 5 (both anchors are depth 5).
    EngineStrength strength = BotEloMapping.forElo(1300);

    assertThat(strength.skillLevel()).isEqualTo(5);
    assertThat(strength.depth()).isEqualTo(5);
  }

  @Test
  void forElo_betweenAnchorsWithDifferentDepths_interpolatesBothSkillAndDepth() {
    // Halfway between tier 5 (1500, skill 7, depth 5) and tier 6 (1900, skill 11, depth 8).
    // skill = round(7 + (11-7)*0.5) = 9; depth = round(5 + (8-5)*0.5) = round(6.5) = 7 (half-up).
    EngineStrength strength = BotEloMapping.forElo(1700);

    assertThat(strength.skillLevel()).isEqualTo(9);
    assertThat(strength.depth()).isEqualTo(7);
  }

  @Test
  void forElo_continuousInput_doesNotSnapToEightBuckets() {
    // A low-but-above-floor Elo must differ from a mid Elo: the mapping is continuous, not
    // bucketed.
    EngineStrength weak = BotEloMapping.forElo(850);
    EngineStrength mid = BotEloMapping.forElo(1500);

    assertThat(weak.skillLevel()).isLessThan(mid.skillLevel());
  }
}
