package io.github.dariogguillen.chess.service.bot;

/**
 * Pure mapping from a requested target Elo to the Fairy-Stockfish search settings that approximate
 * it (feature 23.7, {@code bot-strength-fairy-stockfish}).
 *
 * <p><strong>Why this exists.</strong> An engine cannot be made to "play at 850 Elo" by a single
 * knob. Official Stockfish's {@code UCI_LimitStrength}/{@code UCI_Elo} (used by feature 23.5)
 * floors at ~1320, so it cannot play like a beginner at all. Lichess solves this with a different
 * model: it runs <strong>Fairy-Stockfish</strong> and weakens it with two knobs together — the
 * {@code Skill Level} option (whose extended {@code -20..20} range, with negatives, is what reaches
 * sub-1320) and a capped search {@code depth}. This class is the port of that model: a total
 * function {@code int -> EngineStrength}.
 *
 * <p><strong>Anchor table (verified against lila).</strong> The eight tiers below are Lichess's
 * play-with-the-computer levels. The {@code (skillLevel, depth)} values are taken verbatim from the
 * {@code SkillLevel} enum in <a
 * href="https://github.com/lichess-org/fishnet/blob/master/src/api.rs">lichess-org/fishnet's {@code
 * src/api.rs}</a> ({@code skill_level()} and {@code depth()} methods) — the authoritative source,
 * since that Rust worker is what actually runs the searches for lila's {@code GET /ai} play. The
 * approximate Elo of each tier is Lichess's own published estimate. Note that lila's tier-7 skill
 * is {@code 16} (older forum summaries say {@code 15}); we follow lila.
 *
 * <pre>
 *   tier | skill | depth | ~Elo
 *     1  |  -9   |   5   | &lt;400
 *     2  |  -5   |   5   |  500
 *     3  |  -1   |   5   |  800
 *     4  |   3   |   5   | 1100
 *     5  |   7   |   5   | 1500
 *     6  |  11   |   8   | 1900
 *     7  |  16   |  13   | 2300
 *     8  |  20   |  22   | 2800+
 * </pre>
 *
 * <p><strong>Interpolation.</strong> Lichess snaps a chosen tier; we keep the public interface
 * continuous (an arbitrary Elo in {@link BotEngine#MIN_BOT_ELO}–{@link BotEngine#MAX_BOT_ELO}) and
 * linearly interpolate {@code skill} and {@code depth} between the two bracketing anchors, rounding
 * each to the nearest int. Below the lowest anchor's Elo we clamp to its settings; above the
 * highest anchor (i.e. up to the {@link BotEngine#MAX_BOT_ELO} ceiling) we clamp to full strength
 * ({@code skill 20 / depth 22}). This way a requested 850 differs from a 1500 even though both sit
 * "between tiers", instead of collapsing the whole range into eight buckets.
 *
 * <p>The class is a stateless, side-effect-free utility — a {@code final class} with a private
 * constructor and one static method, the same shape as {@link UciMove}. It is the single source of
 * truth for the strength model and is unit-tested at the tier boundaries, between them, and at the
 * floor/ceiling.
 */
public final class BotEloMapping {

  /** The Elo coordinate of each Lichess tier, ascending; index-aligned with {@link #ANCHORS}. */
  private static final int[] ANCHOR_ELOS = {400, 500, 800, 1100, 1500, 1900, 2300, 3190};

  /**
   * The {@code (skillLevel, depth)} of each Lichess tier, index-aligned with {@link #ANCHOR_ELOS}.
   * The last anchor sits at {@link BotEngine#MAX_BOT_ELO} rather than the published ~2800 so the
   * documented ceiling maps cleanly to full strength.
   */
  private static final EngineStrength[] ANCHORS = {
    new EngineStrength(-9, 5),
    new EngineStrength(-5, 5),
    new EngineStrength(-1, 5),
    new EngineStrength(3, 5),
    new EngineStrength(7, 5),
    new EngineStrength(11, 8),
    new EngineStrength(16, 13),
    new EngineStrength(20, 22),
  };

  private BotEloMapping() {}

  /**
   * Maps a target Elo to the Fairy-Stockfish {@code (Skill Level, depth)} that approximates it.
   *
   * @param elo the requested strength. Expected within {@link BotEngine#MIN_BOT_ELO}–{@link
   *     BotEngine#MAX_BOT_ELO}; values below the lowest anchor clamp to the weakest tier and values
   *     above the highest clamp to full strength, so any int is safe.
   * @return the interpolated settings; {@code skillLevel} within Fairy's {@code -20..20} range and
   *     {@code depth} a small positive int. Never null.
   */
  public static EngineStrength forElo(int elo) {
    if (elo <= ANCHOR_ELOS[0]) {
      return ANCHORS[0];
    }
    int last = ANCHOR_ELOS.length - 1;
    if (elo >= ANCHOR_ELOS[last]) {
      return ANCHORS[last];
    }
    // Find the bracketing anchors [lo, hi] with ANCHOR_ELOS[lo] <= elo < ANCHOR_ELOS[hi].
    int hi = 1;
    while (ANCHOR_ELOS[hi] <= elo) {
      hi++;
    }
    int lo = hi - 1;
    double fraction = (double) (elo - ANCHOR_ELOS[lo]) / (ANCHOR_ELOS[hi] - ANCHOR_ELOS[lo]);
    int skill = interpolate(ANCHORS[lo].skillLevel(), ANCHORS[hi].skillLevel(), fraction);
    int depth = interpolate(ANCHORS[lo].depth(), ANCHORS[hi].depth(), fraction);
    return new EngineStrength(skill, depth);
  }

  /** Linear interpolation between two ints, rounded to the nearest int. */
  private static int interpolate(int from, int to, double fraction) {
    return (int) Math.round(from + (to - from) * fraction);
  }
}
