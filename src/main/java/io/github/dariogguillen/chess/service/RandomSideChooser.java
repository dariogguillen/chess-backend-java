package io.github.dariogguillen.chess.service;

import io.github.dariogguillen.chess.domain.Side;
import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/**
 * Resolves a {@code RANDOM} side preference to a concrete {@link Side} with a server-side coin
 * flip.
 *
 * <p>The randomness lives behind this small seam — rather than inline in {@code RoomService} — for
 * two reasons. First, <strong>anti-cheat</strong>: the coin flip is server-authoritative, so a
 * client asking for a random side cannot bias the outcome (same posture as {@link
 * RoomCodeGenerator}, which owns room-code randomness). Second, <strong>testability</strong>: a
 * unit test can stub this component to make the {@code RANDOM} branch deterministic, exactly as the
 * injected {@code Clock} seam makes time-dependent code deterministic elsewhere in the codebase.
 *
 * <p>Backed by a single thread-safe {@link SecureRandom}; the choice is uniform over the two sides.
 */
@Component
public class RandomSideChooser {

  private final SecureRandom random = new SecureRandom();

  /**
   * Returns a uniformly random concrete {@link Side}.
   *
   * @return {@link Side#WHITE} or {@link Side#BLACK}, each with probability one half.
   */
  public Side choose() {
    return random.nextBoolean() ? Side.WHITE : Side.BLACK;
  }
}
