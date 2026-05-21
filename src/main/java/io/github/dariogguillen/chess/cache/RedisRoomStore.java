package io.github.dariogguillen.chess.cache;

import io.github.dariogguillen.chess.config.RedisActiveStateProperties;
import io.github.dariogguillen.chess.domain.Room;
import io.github.dariogguillen.chess.service.RoomStore;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.locks.Lock;
import java.util.function.BiFunction;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis-backed implementation of {@link RoomStore}. Keys live under the {@code room:{id}}
 * namespace; values are serialized as JSON by the {@link RedisTemplate} configured in {@code
 * RedisConfig}.
 *
 * <p>Every {@code save} sets or refreshes a TTL on the key, sourced from {@link
 * RedisActiveStateProperties#activeStateTtl()}. Reads do not refresh the TTL — that is the contract
 * that makes abandoned rooms self-clean after the configured lease elapses (24 hours by default).
 *
 * <p>Atomicity for {@link #compute(String, BiFunction)} is delegated to a process-local {@link
 * StripedKeyLock}: the read-modify-write block runs inside a per-room-id {@link Lock}, so two
 * concurrent joins on the same room serialize on the same lock. This matches the semantics that the
 * previous {@code InMemoryRoomStore} inherited from {@code ConcurrentHashMap.compute}. The
 * trade-off (vs. {@code WATCH/MULTI/EXEC} or Lua) is documented in {@code
 * notes/08-redis-active-state.md}; in short, the backend runs single-instance, so a JVM- local lock
 * is enough.
 */
@Component
public class RedisRoomStore implements RoomStore {

  private static final String KEY_PREFIX = "room:";

  private final RedisTemplate<String, Room> redisTemplate;
  private final StripedKeyLock locks;
  private final Duration ttl;

  public RedisRoomStore(
      RedisTemplate<String, Room> roomRedisTemplate, RedisActiveStateProperties properties) {
    this.redisTemplate = roomRedisTemplate;
    this.locks = new StripedKeyLock();
    this.ttl = properties.activeStateTtl();
  }

  @Override
  public Optional<Room> findById(String id) {
    return Optional.ofNullable(redisTemplate.opsForValue().get(key(id)));
  }

  @Override
  public void save(Room room) {
    redisTemplate.opsForValue().set(key(room.id()), room, ttl);
  }

  @Override
  public Room compute(String id, BiFunction<String, Room, Room> remappingFunction) {
    Lock lock = locks.get(id);
    lock.lock();
    try {
      String key = key(id);
      Room current = redisTemplate.opsForValue().get(key);
      Room next = remappingFunction.apply(id, current);
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
