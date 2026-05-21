package io.github.dariogguillen.chess.cache;

import io.github.dariogguillen.chess.config.RedisActiveStateProperties;
import io.github.dariogguillen.chess.domain.Game;
import io.github.dariogguillen.chess.service.GameStore;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.locks.Lock;
import java.util.function.BiFunction;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis-backed implementation of {@link GameStore}. Keys live under the {@code game:{id}}
 * namespace; values are serialized as JSON by the {@link RedisTemplate} configured in {@code
 * RedisConfig}.
 *
 * <p>The contract mirrors {@link RedisRoomStore} on a different keyspace: every {@code save} sets
 * or refreshes the TTL from {@link RedisActiveStateProperties#activeStateTtl()}; reads do not
 * refresh; {@link #compute(String, BiFunction)} runs the remapping function inside a process-local
 * {@link Lock} keyed by game id, serializing concurrent move applications on the same game while
 * letting different games proceed in parallel.
 *
 * <p>The store is unaware of the cross-store invariant "a game exists iff its room is ACTIVE" —
 * that invariant lives in {@code RoomService.joinRoom}, which performs the {@code gameStore.save}
 * inside the {@code roomStore.compute} block.
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
  public Optional<Game> findById(String id) {
    return Optional.ofNullable(redisTemplate.opsForValue().get(key(id)));
  }

  @Override
  public void save(Game game) {
    redisTemplate.opsForValue().set(key(game.id()), game, ttl);
  }

  @Override
  public Game compute(String id, BiFunction<String, Game, Game> remappingFunction) {
    Lock lock = locks.get(id);
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

  private static String key(String id) {
    return KEY_PREFIX + id;
  }
}
