package io.github.dariogguillen.chess.config;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed binding for the {@code chess.redis.*} configuration namespace. Currently exposes a single
 * knob, {@link #activeStateTtl()}, used by the Redis-backed stores ({@code RedisRoomStore}, {@code
 * RedisGameStore}) to set / refresh the per-key TTL on every {@code save}. Reads do not refresh the
 * TTL; only mutations do — that is the contract that gives the "abandoned room self-cleans after
 * 24h" behavior.
 *
 * <p>Spring's relaxed binding accepts any {@link Duration}-compatible value in {@code
 * application.yml}: {@code 24h}, {@code PT24H}, {@code 86400s}, {@code 1d}. The default of 24 hours
 * applies when the property is absent.
 *
 * @param activeStateTtl how long an active-state key (room or game) lives in Redis after the most
 *     recent write. Must be strictly positive — a zero or negative TTL would either delete the key
 *     immediately ({@code 0}) or be rejected by Redis ({@code &lt;0}), neither of which is a useful
 *     mode for this feature.
 */
@ConfigurationProperties("chess.redis")
public record RedisActiveStateProperties(Duration activeStateTtl) {

  /** Default TTL used when {@code chess.redis.active-state-ttl} is absent from configuration. */
  public static final Duration DEFAULT_ACTIVE_STATE_TTL = Duration.ofHours(24);

  public RedisActiveStateProperties {
    if (activeStateTtl == null) {
      activeStateTtl = DEFAULT_ACTIVE_STATE_TTL;
    }
    Objects.requireNonNull(activeStateTtl, "activeStateTtl");
    if (activeStateTtl.isZero() || activeStateTtl.isNegative()) {
      throw new IllegalArgumentException(
          "chess.redis.active-state-ttl must be strictly positive, got: " + activeStateTtl);
    }
  }
}
