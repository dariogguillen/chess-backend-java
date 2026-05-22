package io.github.dariogguillen.chess.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import io.github.dariogguillen.chess.config.DisconnectProperties;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Unit coverage for {@link GracePeriodManager}'s timer lifecycle. Uses a real {@link
 * ThreadPoolTaskScheduler} with a short grace period (50 ms) plus an Awaitility wait so the fire
 * path is exercised end-to-end without coupling the test to wall-clock sleeps. {@link
 * GameAbandonService} is mocked because this unit test cares about whether the manager schedules,
 * cancels, and fires correctly — not what the abandon path itself does.
 */
@ExtendWith(MockitoExtension.class)
class GracePeriodManagerTest {

  private static final Duration GRACE = Duration.ofMillis(50);

  @Mock private GameAbandonService gameAbandonService;

  private ThreadPoolTaskScheduler scheduler;
  private GracePeriodManager manager;

  @BeforeEach
  void setUp() {
    scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(2);
    scheduler.setThreadNamePrefix("test-scheduler-");
    scheduler.initialize();
    manager =
        new GracePeriodManager(
            scheduler, Clock.systemUTC(), new DisconnectProperties(GRACE), gameAbandonService);
  }

  @AfterEach
  void tearDown() {
    scheduler.shutdown();
  }

  @Test
  void startGracePeriod_thenIsActive_returnsTrue() {
    UUID playerId = UUID.randomUUID();
    UUID gameId = UUID.randomUUID();

    manager.startGracePeriod(playerId, gameId);

    assertThat(manager.isActive(playerId, gameId)).isTrue();
  }

  @Test
  void startGracePeriod_thenCancel_isActiveReturnsFalse_andTaskNeverFires() throws Exception {
    UUID playerId = UUID.randomUUID();
    UUID gameId = UUID.randomUUID();

    manager.startGracePeriod(playerId, gameId);
    manager.cancelGracePeriod(playerId, gameId);

    assertThat(manager.isActive(playerId, gameId)).isFalse();
    // Wait well past the grace window; nothing should have fired.
    Thread.sleep(GRACE.toMillis() * 4);
    verifyNoInteractions(gameAbandonService);
  }

  @Test
  void startGracePeriod_calledTwice_secondCallReplacesFirst_onlyOneInvocation() {
    UUID playerId = UUID.randomUUID();
    UUID gameId = UUID.randomUUID();

    manager.startGracePeriod(playerId, gameId);
    manager.startGracePeriod(playerId, gameId);

    // The fire should still happen exactly once after the grace period — the second start
    // cancelled the first.
    await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(() -> verify(gameAbandonService, times(1)).abandon(gameId, playerId));
    assertThat(manager.isActive(playerId, gameId)).isFalse();
  }

  @Test
  void startGracePeriod_thenTaskFires_invokesGameAbandonService_andClearsMap() {
    UUID playerId = UUID.randomUUID();
    UUID gameId = UUID.randomUUID();

    manager.startGracePeriod(playerId, gameId);

    await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(() -> verify(gameAbandonService).abandon(gameId, playerId));
    assertThat(manager.isActive(playerId, gameId)).isFalse();
  }

  @Test
  void cancelGracePeriod_onUnknownKey_isNoop() {
    UUID playerId = UUID.randomUUID();
    UUID gameId = UUID.randomUUID();

    manager.cancelGracePeriod(playerId, gameId);

    assertThat(manager.isActive(playerId, gameId)).isFalse();
    verify(gameAbandonService, never()).abandon(gameId, playerId);
  }
}
