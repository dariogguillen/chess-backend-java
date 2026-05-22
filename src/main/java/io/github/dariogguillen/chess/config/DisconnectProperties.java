package io.github.dariogguillen.chess.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed binding for the {@code chess.disconnect.*} configuration namespace. Currently exposes a
 * single knob, {@link #gracePeriod()}, that the {@code GracePeriodManager} reads to schedule the
 * one-shot abandon timer that fires when a player loses their STOMP session and does not
 * resubscribe within the window.
 *
 * <p>Spring's relaxed binding accepts any {@link Duration}-compatible value in {@code
 * application.yml}: {@code 60s}, {@code PT60S}, {@code 1m}, {@code 200ms} (used by the integration
 * tests via {@code @TestPropertySource} so the timer fires in well under a second instead of
 * hanging for the full minute).
 *
 * @param gracePeriod how long the server waits after a player's STOMP {@code DISCONNECT} before
 *     mutating the game to {@code ABANDONED}. Must be strictly positive — a zero or negative value
 *     would either fire immediately (no grace at all) or be rejected by the scheduler, neither of
 *     which is a useful mode for this feature.
 */
@ConfigurationProperties("chess.disconnect")
public record DisconnectProperties(Duration gracePeriod) {

  public DisconnectProperties {
    if (gracePeriod == null || gracePeriod.isZero() || gracePeriod.isNegative()) {
      throw new IllegalStateException(
          "chess.disconnect.grace-period must be strictly positive, got: " + gracePeriod);
    }
  }
}
