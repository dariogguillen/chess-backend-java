package io.github.dariogguillen.chess.service;

import io.github.dariogguillen.chess.cache.StripedKeyLock;
import io.github.dariogguillen.chess.config.DisconnectProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.locks.Lock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * In-memory registry of pending "abandon this game on timeout" timers, one per {@code (playerId,
 * gameId)} pair. The single owner of the timer lifecycle: created on STOMP disconnect, cancelled on
 * reconnect, or fired-then-removed on timeout.
 *
 * <p>Public surface:
 *
 * <ul>
 *   <li>{@link #startGracePeriod(UUID, UUID)} — schedule the abandon at {@code now() +
 *       gracePeriod}. If a timer already exists for the key, it is cancelled and replaced (rare:
 *       rapid disconnect-reconnect-disconnect within ms).
 *   <li>{@link #cancelGracePeriod(UUID, UUID)} — the reconnect path. Cancels and removes the
 *       pending timer; a no-op if no timer is active.
 *   <li>{@link #isActive(UUID, UUID)} — read-only "is there a pending timer for this pair?"
 *       (currently unused by callers but provided for diagnostics / tests).
 * </ul>
 *
 * <p>Concurrency model: each public method acquires the per-key lock from the reused {@link
 * StripedKeyLock} before touching the {@code active} map. The task body the scheduler runs also
 * acquires the same lock before removing itself from the map and invoking the abandon path — so a
 * {@code cancel} call and a fire-then-remove cannot interleave. Without this discipline, the cancel
 * could remove an entry from the map while the task body races to do the same, leaving the abandon
 * path to run on a game whose timer the caller believes it already cancelled.
 *
 * <p>Server-restart limitation: the {@code active} map is process-local. If the JVM restarts with
 * pending timers, those games stay in their non-terminal status until something else terminates
 * them (a move, or a fresh disconnect that schedules a new timer). This is acknowledged in {@code
 * notes/11-disconnect-handling.md} and is a deliberate trade-off — persisting timers to Redis with
 * a restart-time recovery sweep is heavyweight for the single-instance deploy.
 */
@Component
public class GracePeriodManager {

  private static final Logger log = LoggerFactory.getLogger(GracePeriodManager.class);

  private final TaskScheduler taskScheduler;
  private final Clock clock;
  private final DisconnectProperties properties;
  private final GameAbandonService gameAbandonService;
  private final StripedKeyLock locks;

  private final Map<GracePeriodKey, ScheduledFuture<?>> active = new ConcurrentHashMap<>();

  public GracePeriodManager(
      TaskScheduler taskScheduler,
      Clock clock,
      DisconnectProperties properties,
      GameAbandonService gameAbandonService) {
    this.taskScheduler = taskScheduler;
    this.clock = clock;
    this.properties = properties;
    this.gameAbandonService = gameAbandonService;
    // StripedKeyLock has no shared state with other consumers' instances — it is just a per-key
    // ReentrantLock cache. A fresh instance is fine here; reusing the one inside RedisGameStore
    // would couple two unrelated concerns through a shared lock identity.
    this.locks = new StripedKeyLock();
  }

  /**
   * Schedules an abandon task at {@code now() + gracePeriod} for the given {@code (playerId,
   * gameId)} pair. If a task is already scheduled for the same key, it is cancelled first and
   * replaced — the common path is the first call, but a fast disconnect-reconnect-disconnect cycle
   * within milliseconds can land two starts in a row.
   *
   * @param playerId the player whose session dropped; non-null.
   * @param gameId the game the player was in; non-null.
   */
  public void startGracePeriod(UUID playerId, UUID gameId) {
    GracePeriodKey key = new GracePeriodKey(playerId, gameId);
    Lock lock = locks.get(key.toString());
    lock.lock();
    try {
      ScheduledFuture<?> existing = active.remove(key);
      if (existing != null) {
        existing.cancel(false);
      }
      Instant fireAt = Instant.now(clock).plus(properties.gracePeriod());
      ScheduledFuture<?> scheduled = taskScheduler.schedule(() -> fire(key), fireAt);
      active.put(key, scheduled);
      log.info("Grace period started: playerId={}, gameId={}, fireAt={}", playerId, gameId, fireAt);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Cancels any pending grace-period timer for the given pair. No-op if no timer is active. Safe to
   * call concurrently with the timer firing: the task body acquires the same per-key lock before
   * removing itself from the map, so a cancel that wins the lock will see-and-remove the
   * still-pending future; a cancel that loses the lock to the firing task will observe the
   * already-removed entry and do nothing.
   *
   * <p>The boolean return distinguishes "we cancelled an in-flight timer" (the reconnect-within-
   * grace path, which {@code PlayerSessionTracker} treats as the trigger for a {@code
   * PlayerReconnectedEvent} broadcast) from "there was nothing to cancel" (a fresh subscribe with
   * no prior disconnect, or a reconnect that arrived after the timer already fired — in which case
   * the {@code GameAbandonedEvent} is the authoritative broadcast and no reconnect event should be
   * emitted).
   *
   * @param playerId the player whose session was just restored; non-null.
   * @param gameId the game the player was in; non-null.
   * @return {@code true} if a pending timer was found and cancelled; {@code false} if there was no
   *     active timer for the pair.
   */
  public boolean cancelGracePeriod(UUID playerId, UUID gameId) {
    GracePeriodKey key = new GracePeriodKey(playerId, gameId);
    Lock lock = locks.get(key.toString());
    lock.lock();
    try {
      ScheduledFuture<?> existing = active.remove(key);
      if (existing != null) {
        existing.cancel(false);
        log.info("Grace period cancelled: playerId={}, gameId={}", playerId, gameId);
        return true;
      }
      return false;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Returns {@code true} if a grace-period timer is currently pending for the given pair. Reads the
   * map directly without acquiring the lock — the value is racy by nature and the caller must treat
   * the result as advisory (e.g. for diagnostics or test assertions on the post-condition of an
   * explicit start/cancel call).
   *
   * @param playerId the player; non-null.
   * @param gameId the game; non-null.
   * @return whether a timer is currently scheduled for this pair.
   */
  public boolean isActive(UUID playerId, UUID gameId) {
    return active.containsKey(new GracePeriodKey(playerId, gameId));
  }

  /**
   * Body of the scheduled task. Acquires the per-key lock so a concurrent {@link
   * #cancelGracePeriod} cannot interleave with the map-remove + abandon call, removes ourselves
   * from the active map (a future {@code cancel} after this point is a no-op), and invokes the
   * abandon path. Any exception is caught and logged at {@code WARN} so a single failed timer
   * cannot poison the scheduler's worker thread for subsequent tasks.
   */
  private void fire(GracePeriodKey key) {
    Lock lock = locks.get(key.toString());
    lock.lock();
    try {
      ScheduledFuture<?> self = active.remove(key);
      if (self == null) {
        // We lost the race to cancelGracePeriod or a concurrent start that replaced us; the new
        // timer (or the absence thereof) is the source of truth, not this fire.
        return;
      }
      gameAbandonService.abandon(key.gameId(), key.playerId());
    } catch (RuntimeException ex) {
      log.warn(
          "Grace-period abandon task failed: playerId={}, gameId={}: {}",
          key.playerId(),
          key.gameId(),
          ex.getMessage());
    } finally {
      lock.unlock();
    }
  }

  /** Composite identity for the {@code (playerId, gameId)} timer key. */
  private record GracePeriodKey(UUID playerId, UUID gameId) {}
}
