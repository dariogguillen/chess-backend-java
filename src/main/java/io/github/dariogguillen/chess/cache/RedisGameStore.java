package io.github.dariogguillen.chess.cache;

import io.github.dariogguillen.chess.config.RedisActiveStateProperties;
import io.github.dariogguillen.chess.domain.Game;
import io.github.dariogguillen.chess.service.GameStore;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.Lock;
import java.util.function.BiFunction;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Component;

/**
 * Redis-backed implementation of {@link GameStore}. Keys live under the {@code game:{id}}
 * namespace; values are serialized as JSON by the {@link RedisTemplate} configured in {@code
 * RedisConfig}.
 *
 * <p>The contract mirrors {@link RedisRoomStore} on a different keyspace: every {@code save} sets
 * or refreshes the TTL from {@link RedisActiveStateProperties#activeStateTtl()}; reads do not
 * refresh; {@link #compute(UUID, BiFunction)} runs the remapping function inside a process-local
 * {@link Lock} keyed by game id, serializing concurrent move applications on the same game while
 * letting different games proceed in parallel.
 *
 * <p>The store is unaware of the cross-store invariant "a game exists iff its room is ACTIVE" —
 * that invariant lives in {@code RoomService.joinRoom}, which performs the {@code gameStore.save}
 * inside the {@code roomStore.compute} block.
 *
 * <p>Game ids are {@link UUID}s end-to-end; the {@link StripedKeyLock} keys on the canonical {@code
 * UUID.toString()} form, and the Redis key is built from the same string so the keyspace stays
 * {@code redis-cli GET game:<uuid>} -inspectable.
 */
@Component
public class RedisGameStore implements GameStore {

  private static final String KEY_PREFIX = "game:";

  private final RedisTemplate<String, Game> redisTemplate;
  private final StripedKeyLock locks;
  private final Duration ttl;

  public RedisGameStore(
      RedisTemplate<String, Game> gameRedisTemplate, RedisActiveStateProperties properties) {
    this.redisTemplate = gameRedisTemplate;
    this.locks = new StripedKeyLock();
    this.ttl = properties.activeStateTtl();
  }

  @Override
  public Optional<Game> findById(UUID id) {
    return Optional.ofNullable(redisTemplate.opsForValue().get(key(id)));
  }

  /**
   * Scans the {@code game:*} keyspace and returns the first game whose {@link Game#roomId()} equals
   * {@code roomId}. The scan is bounded by the count of active games on this single-instance
   * deployment; the room-to-game relationship is one-to-(at-most-)one so the loop returns on the
   * first hit. {@code SCAN} is preferred over {@code KEYS} (non-blocking, cursor-based) per Redis'
   * own recommendation for production-side iteration. Cost is {@code O(N)} over the {@code game:*}
   * keyspace; N is bounded by the 24h TTL and the single-instance traffic, and the method is only
   * invoked once per {@code GET /api/rooms/{id}} call (no hot path).
   */
  @Override
  public Optional<Game> findByRoomId(String roomId) {
    ScanOptions options = ScanOptions.scanOptions().match(KEY_PREFIX + "*").count(64).build();
    try (Cursor<String> cursor = redisTemplate.scan(options)) {
      while (cursor.hasNext()) {
        String key = cursor.next();
        Game game = redisTemplate.opsForValue().get(key);
        if (game != null && roomId.equals(game.roomId())) {
          return Optional.of(game);
        }
      }
    }
    return Optional.empty();
  }

  @Override
  public void save(Game game) {
    redisTemplate.opsForValue().set(key(game.id()), game, ttl);
  }

  @Override
  public Game compute(UUID id, BiFunction<UUID, Game, Game> remappingFunction) {
    Lock lock = locks.get(id.toString());
    lock.lock();
    try {
      String key = key(id);
      Game current = redisTemplate.opsForValue().get(key);
      Game next = remappingFunction.apply(id, current);
      if (next == null) {
        redisTemplate.delete(key);
        return null;
      }
      redisTemplate.opsForValue().set(key, next, ttl);
      return next;
    } finally {
      lock.unlock();
    }
  }

  private static String key(UUID id) {
    return KEY_PREFIX + id;
  }
}
