package io.github.dariogguillen.chess.service;

import io.github.dariogguillen.chess.cache.StripedKeyLock;
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
 * In-memory registry of pending "flag this game on the clock" timers, one per {@code gameId}
 * (feature 22, {@code time-control}). The single owner of the flag-timer lifecycle: scheduled when
 * a timed game starts (white's clock) and on every move (the new side-to-move's clock), cancelled
 * on a terminal outcome, or fired-then-removed when the side-to-move's time elapses.
 *
 * <p>The spiritual twin of {@code GracePeriodManager}: same {@link TaskScheduler} + {@link Clock}
 * beans, same {@link StripedKeyLock}-guarded concurrent map of {@link ScheduledFuture}, same
 * fire-then-remove discipline. The differences are the key (here a bare {@code gameId}, not a
 * {@code (playerId, gameId)} pair — there is one clock per game, not one per player) and the target
 * ({@code GameTimeoutService.timeout} rather than {@code GameAbandonService.abandon}).
 *
 * <p>Public surface:
 *
 * <ul>
 *   <li>{@link #scheduleFlag(UUID, Instant)} — schedule (or replace) the flag at an absolute
 *       deadline.
 *   <li>{@link #cancel(UUID)} — cancel any pending flag; the terminal-outcome path.
 *   <li>{@link #isActive(UUID)} — read-only "is there a pending flag for this game?" (diagnostics /
 *       tests).
 * </ul>
 *
 * <p>Concurrency model: each public method acquires the per-key lock before touching the {@code
 * active} map; the fired task body acquires the same lock before removing itself and invoking the
 * timeout path, so a {@code cancel} and a fire-then-remove cannot interleave. Identical to {@code
 * GracePeriodManager}.
 *
 * <p>Server-restart limitation: the {@code active} map is process-local. If the JVM restarts with
 * pending flag timers, those timed games stay non-terminal until something else terminates them (a
 * move, a disconnect-grace timeout). Same documented trade-off as {@code GracePeriodManager} — a
 * Redis-persisted timer with a restart sweep is heavyweight for the single-instance deploy.
 */
@Component
public class ClockTimerManager {

  private static final Logger log = LoggerFactory.getLogger(ClockTimerManager.class);

  private final TaskScheduler taskScheduler;
  private final Clock clock;
  private final GameTimeoutService gameTimeoutService;
  private final StripedKeyLock locks;

  private final Map<UUID, ScheduledFuture<?>> active = new ConcurrentHashMap<>();

  public ClockTimerManager(
      TaskScheduler taskScheduler, Clock clock, GameTimeoutService gameTimeoutService) {
    this.taskScheduler = taskScheduler;
    this.clock = clock;
    this.gameTimeoutService = gameTimeoutService;
    // A fresh StripedKeyLock — the same rationale as GracePeriodManager: a per-key ReentrantLock
    // cache with no shared state, kept independent of any other consumer's lock identity.
    this.locks = new StripedKeyLock();
  }

  /**
   * Schedules a flag task at the absolute {@code deadline} for the given game. If a flag is already
   * scheduled for the same game (the common case on every move), it is cancelled first and replaced
   * — the clock for the new side-to-move supersedes the previous side's pending flag. A deadline in
   * the past fires effectively immediately; the {@link TaskScheduler} accepts a past {@link
   * Instant} and runs the task as soon as a worker thread is free.
   *
   * @param gameId the game whose clock to arm; non-null.
   * @param deadline the absolute instant the side-to-move's time runs out; non-null.
   */
  public void scheduleFlag(UUID gameId, Instant deadline) {
    Lock lock = locks.get(gameId.toString());
    lock.lock();
    try {
      ScheduledFuture<?> existing = active.remove(gameId);
      if (existing != null) {
        existing.cancel(false);
      }
      ScheduledFuture<?> scheduled = taskScheduler.schedule(() -> fire(gameId), deadline);
      active.put(gameId, scheduled);
      log.info("Clock flag scheduled: gameId={}, deadline={}", gameId, deadline);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Cancels any pending flag timer for the given game. No-op if none is active. The
   * terminal-outcome path: called by {@code GameService.applyMove} when a move ends the game
   * (checkmate, stalemate, draw) so a now-irrelevant flag does not fire on an already-terminal
   * game. Safe to call concurrently with the timer firing — same lock discipline as {@code
   * GracePeriodManager.cancelGracePeriod}.
   *
   * @param gameId the game whose flag to cancel; non-null.
   */
  public void cancel(UUID gameId) {
    Lock lock = locks.get(gameId.toString());
    lock.lock();
    try {
      ScheduledFuture<?> existing = active.remove(gameId);
      if (existing != null) {
        existing.cancel(false);
        log.info("Clock flag cancelled: gameId={}", gameId);
      }
    } finally {
      lock.unlock();
    }
  }

  /**
   * Returns {@code true} if a flag timer is currently pending for the given game. Reads the map
   * directly without the lock — advisory only, for diagnostics or test post-condition assertions.
   *
   * @param gameId the game; non-null.
   * @return whether a flag timer is currently scheduled for this game.
   */
  public boolean isActive(UUID gameId) {
    return active.containsKey(gameId);
  }

  /**
   * Body of the scheduled flag task. Acquires the per-key lock so a concurrent {@link #cancel} or
   * {@link #scheduleFlag} (a move that just landed) cannot interleave with the map-remove + timeout
   * call, removes ourselves from the active map (a future {@code cancel} after this point is a
   * no-op), and invokes the timeout path. Any exception is caught and logged at {@code WARN} so a
   * single failed timer cannot poison the scheduler's worker thread.
   */
  private void fire(UUID gameId) {
    Lock lock = locks.get(gameId.toString());
    lock.lock();
    try {
      ScheduledFuture<?> self = active.remove(gameId);
      if (self == null) {
        // We lost the race to a cancel or a re-schedule that replaced us; the new timer (or its
        // absence) is the source of truth, not this fire.
        return;
      }
      gameTimeoutService.timeout(gameId);
    } catch (RuntimeException ex) {
      log.warn("Clock flag task failed: gameId={}: {}", gameId, ex.getMessage());
    } finally {
      lock.unlock();
    }
  }
}
