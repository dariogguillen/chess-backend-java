package io.github.dariogguillen.chess.cache;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dariogguillen.chess.TestcontainersConfiguration;
import io.github.dariogguillen.chess.config.RedisActiveStateProperties;
import io.github.dariogguillen.chess.domain.Player;
import io.github.dariogguillen.chess.domain.Room;
import io.github.dariogguillen.chess.domain.RoomStatus;
import io.github.dariogguillen.chess.service.RoomStore;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * Asserts that the Redis-backed stores apply and refresh the per-key TTL on every {@code save}.
 * Uses a short, dedicated TTL of 30 seconds so the test does not depend on the production default
 * of 24 hours but can still observe the refresh behaviour deterministically.
 *
 * <p>The {@code @TestPropertySource} below overrides {@code chess.redis.active-state-ttl} for this
 * IT only. The override is the same mechanism the production deployment uses (via the {@code
 * CHESS_REDIS_ACTIVE_STATE_TTL} env var documented in {@code application.yml}) — the binding is
 * exercised end-to-end through {@link RedisActiveStateProperties}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestPropertySource(properties = "chess.redis.active-state-ttl=30s")
class RedisTtlIT {

  private static final Duration CONFIGURED_TTL = Duration.ofSeconds(30);

  @Autowired private RoomStore roomStore;

  @Autowired private RedisTemplate<String, Room> roomRedisTemplate;

  @Autowired private RedisActiveStateProperties properties;

  @Test
  void configuredTtl_isBoundFromTestPropertySource() {
    // Guards against a future refactor where the @TestPropertySource override stops taking
    // effect — every assertion below depends on the configured TTL matching CONFIGURED_TTL.
    assertThat(properties.activeStateTtl()).isEqualTo(CONFIGURED_TTL);
  }

  @Test
  void save_setsTtlCloseToConfiguredValue() {
    String id = uniqueRoomId();
    Player alice = new Player(UUID.randomUUID().toString(), "Alice");
    roomStore.save(new Room(id, List.of(alice), RoomStatus.WAITING_FOR_PLAYER));

    Long ttlMillis = roomRedisTemplate.getExpire("room:" + id, TimeUnit.MILLISECONDS);
    assertThat(ttlMillis).isNotNull();
    // The expiry must be set (positive) and not exceed the configured TTL. We allow a small
    // tolerance below the configured value to account for the elapsed time between SET and PTTL.
    assertThat(ttlMillis).isPositive();
    assertThat(ttlMillis).isLessThanOrEqualTo(CONFIGURED_TTL.toMillis());
    assertThat(ttlMillis).isGreaterThan(CONFIGURED_TTL.toMillis() - 5_000L);
  }

  @Test
  void resave_refreshesTtl() throws Exception {
    String id = uniqueRoomId();
    Player alice = new Player(UUID.randomUUID().toString(), "Alice");
    Room room = new Room(id, List.of(alice), RoomStatus.WAITING_FOR_PLAYER);
    roomStore.save(room);

    // Sleep enough that, without a refresh, the remaining TTL would be visibly lower than the
    // configured value. 1.5 seconds is well below 30s but above the noise floor of getExpire.
    Thread.sleep(1_500);

    Long ttlBeforeRefresh = roomRedisTemplate.getExpire("room:" + id, TimeUnit.MILLISECONDS);
    assertThat(ttlBeforeRefresh).isNotNull();
    assertThat(ttlBeforeRefresh).isLessThan(CONFIGURED_TTL.toMillis() - 1_000L);

    // A second save must reset the TTL to ~CONFIGURED_TTL.
    roomStore.save(room);

    Long ttlAfterRefresh = roomRedisTemplate.getExpire("room:" + id, TimeUnit.MILLISECONDS);
    assertThat(ttlAfterRefresh).isNotNull();
    assertThat(ttlAfterRefresh).isGreaterThan(ttlBeforeRefresh);
    assertThat(ttlAfterRefresh).isGreaterThan(CONFIGURED_TTL.toMillis() - 5_000L);
  }

  @Test
  void compute_refreshesTtl_onSuccessfulWrite() throws Exception {
    String id = uniqueRoomId();
    Player alice = new Player(UUID.randomUUID().toString(), "Alice");
    Player bob = new Player(UUID.randomUUID().toString(), "Bob");
    Room initial = new Room(id, List.of(alice), RoomStatus.WAITING_FOR_PLAYER);
    roomStore.save(initial);

    Thread.sleep(1_500);
    Long ttlBefore = roomRedisTemplate.getExpire("room:" + id, TimeUnit.MILLISECONDS);
    assertThat(ttlBefore).isNotNull();
    assertThat(ttlBefore).isLessThan(CONFIGURED_TTL.toMillis() - 1_000L);

    roomStore.compute(id, (key, existing) -> new Room(key, List.of(alice, bob), RoomStatus.ACTIVE));

    Long ttlAfter = roomRedisTemplate.getExpire("room:" + id, TimeUnit.MILLISECONDS);
    assertThat(ttlAfter).isNotNull();
    assertThat(ttlAfter).isGreaterThan(ttlBefore);
    assertThat(ttlAfter).isGreaterThan(CONFIGURED_TTL.toMillis() - 5_000L);
  }

  private static String uniqueRoomId() {
    return "ITTTL-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
  }
}
