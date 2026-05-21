package io.github.dariogguillen.chess.cache;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Per-key local lock registry. Returns the same {@link ReentrantLock} instance every time it is
 * asked for a given key, so two threads contending on the same key serialize on the same monitor,
 * while threads operating on different keys proceed independently.
 *
 * <p>Used by {@code RedisRoomStore} and {@code RedisGameStore} to give the {@code compute(id, fn)}
 * call the same per-id atomicity that {@code ConcurrentHashMap.compute} provides for the in-memory
 * stores. Redis has no native primitive for "run Java logic atomically against a value"; the
 * production deployment runs a single backend instance, so a JVM-local lock is sufficient. If we
 * ever go multi-instance, the upgrade path is to replace this lock with a Redis-side mechanism (a
 * {@code WATCH/MULTI/EXEC} loop, a Redlock-style distributed lock, or a Lua script) without
 * changing any caller — the {@code RoomStore} / {@code GameStore} interfaces are unaffected.
 *
 * <p>The implementation is intentionally minimal:
 *
 * <ul>
 *   <li>Lock acquisition is {@code O(1)} via {@link ConcurrentMap#computeIfAbsent}.
 *   <li>Locks are <em>never evicted</em> from the map. The number of distinct keys a single backend
 *       handles in its lifetime is bounded by the number of distinct rooms and games ever created
 *       on that JVM, which for a portfolio-scale deployment is small. If this ever becomes a memory
 *       concern, a {@code WeakReference}-based map or a fixed-size striped scheme would replace
 *       this implementation behind the same {@code Lock get(String)} contract.
 *   <li>{@link ReentrantLock}s are returned, so the same thread re-entering on the same key does
 *       not deadlock itself — useful if a {@code compute} lambda calls back into the same store.
 * </ul>
 */
public class StripedKeyLock {

  private final ConcurrentMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

  /**
   * Returns the {@link Lock} for {@code key}, creating it on first request. Subsequent calls with
   * the same key return the same instance.
   *
   * @param key the key to lock on; non-null.
   * @return the lock associated with {@code key}; never null, never a fresh instance after the
   *     first call for the same key.
   */
  public Lock get(String key) {
    return locks.computeIfAbsent(key, k -> new ReentrantLock());
  }
}
