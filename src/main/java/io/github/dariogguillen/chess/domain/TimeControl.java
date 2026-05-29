package io.github.dariogguillen.chess.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A declared time control for a chess game (feature 22, {@code time-control}). Two knobs:
 *
 * <ul>
 *   <li>{@code initialMs} — the starting clock per side, in milliseconds. Strictly positive.
 *   <li>{@code incrementMs} — the Fischer increment added back to a player's clock after they move,
 *       in milliseconds. Zero (the default for plain sudden-death) or positive.
 * </ul>
 *
 * <p>The pair lets a room declare formats such as 5+3 (a 300000ms initial, 3000ms increment) or
 * plain 5+0 (300000ms initial, 0 increment). The MVP carries a single time control per room; the
 * record shape leaves room for future formats (e.g. byo-yomi, delay) without an API break.
 *
 * <p>This is the request-and-domain-side value object. When a room declares it, the joiner-time
 * game initialises both sides' remaining time to {@code initialMs}; on each move the mover's
 * remaining time is decremented by the elapsed wall-clock and {@code incrementMs} is added back.
 * The full clock arithmetic lives in {@code GameService.applyMove}.
 *
 * @param initialMs the starting time per side in milliseconds; strictly positive.
 * @param incrementMs the Fischer increment in milliseconds added after each move; zero or positive.
 */
public record TimeControl(
    @Schema(
            description = "Starting clock per side, in milliseconds. Strictly positive.",
            example = "300000")
        long initialMs,
    @Schema(
            description =
                "Fischer increment added back to a player's clock after they move, in "
                    + "milliseconds. Zero for plain sudden-death.",
            example = "3000")
        long incrementMs) {

  public TimeControl {
    if (initialMs <= 0) {
      throw new IllegalArgumentException(
          "TimeControl initialMs must be strictly positive, got: " + initialMs);
    }
    if (incrementMs < 0) {
      throw new IllegalArgumentException(
          "TimeControl incrementMs must not be negative, got: " + incrementMs);
    }
  }
}
