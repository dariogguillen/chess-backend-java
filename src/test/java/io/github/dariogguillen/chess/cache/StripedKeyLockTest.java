package io.github.dariogguillen.chess.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link StripedKeyLock}. The IT path covers the lock <em>used</em> by the stores;
 * these tests cover the contract of the helper itself — same key returns the same lock instance,
 * different keys return distinct instances, the lock is reentrant, and concurrent acquisitions on
 * the same key serialize.
 */
class StripedKeyLockTest {

  @Test
  void get_sameKey_returnsSameLockInstance() {
    StripedKeyLock locks = new StripedKeyLock();

    Lock first = locks.get("room-1");
    Lock second = locks.get("room-1");

    assertThat(second).isSameAs(first);
  }

  @Test
  void get_differentKeys_returnsDistinctLocks() {
    StripedKeyLock locks = new StripedKeyLock();

    Lock a = locks.get("room-a");
    Lock b = locks.get("room-b");

    assertThat(a).isNotSameAs(b);
  }

  @Test
  void lock_isReentrant_sameThreadDoesNotDeadlock() {
    StripedKeyLock locks = new StripedKeyLock();
    Lock lock = locks.get("game-1");

    lock.lock();
    try {
      // Re-entering the same lock from the same thread must succeed; ReentrantLock is the
      // documented behaviour and the stores rely on it if a compute lambda ever calls back in.
      lock.lock();
      try {
        assertThat(true).isTrue();
      } finally {
        lock.unlock();
      }
    } finally {
      lock.unlock();
    }
  }

  @Test
  void lock_sameKey_serializesConcurrentSections() throws Exception {
    StripedKeyLock locks = new StripedKeyLock();
    String key = "room-shared";
    AtomicInteger insideCritical = new AtomicInteger(0);
    AtomicInteger maxObserved = new AtomicInteger(0);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(2);

    Runnable task =
        () -> {
          try {
            start.await();
            Lock lock = locks.get(key);
            lock.lock();
            try {
              int count = insideCritical.incrementAndGet();
              maxObserved.accumulateAndGet(count, Math::max);
              // Hold long enough that, absent serialization, the other thread would observe
              // a count of 2 here.
              Thread.sleep(50);
              insideCritical.decrementAndGet();
            } finally {
              lock.unlock();
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            done.countDown();
          }
        };

    Thread t1 = new Thread(task, "striped-1");
    Thread t2 = new Thread(task, "striped-2");
    t1.start();
    t2.start();
    start.countDown();

    assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(maxObserved.get()).isEqualTo(1);
  }
}
