package io.github.dariogguillen.chess.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Scheduling infrastructure for the disconnect-handling feature.
 *
 * <p>{@link EnableScheduling} brings up Spring's scheduling support so a {@link TaskScheduler} bean
 * is available for programmatic submission. We do <em>not</em> use {@code @Scheduled} cron / fixed-
 * delay declarative tasks anywhere; the grace-period timer is a one-shot, per-{@code (playerId,
 * gameId)} pair fired at an absolute {@link java.time.Instant} and cancellable on reconnect. The
 * declarative {@code @Scheduled} annotation does not fit that shape — its tasks are recurring and
 * not addressable for cancellation. The programmatic {@code TaskScheduler.schedule(Runnable,
 * Instant)} API returns a {@link java.util.concurrent.ScheduledFuture} we can both hold and cancel,
 * which is the primitive {@code GracePeriodManager} is built on.
 *
 * <p>Pool sizing: the active set is bounded by "number of concurrently-active players whose STOMP
 * session has just dropped" — at portfolio scale, never more than a handful at once. A pool size of
 * 2 with daemon threads is sufficient: the abandon path (mutate Redis, archive to Postgres,
 * broadcast STOMP) is short, low-CPU, and not expected to overlap heavily with itself. The daemon
 * flag prevents the JVM from being held alive by an in-flight scheduler thread during shutdown.
 *
 * <p>Also enables {@link DisconnectProperties} so the {@code chess.disconnect.grace-period} binding
 * is available wherever {@code @EnableConfigurationProperties} is honored — mirroring the {@code
 * CorsConfig} / {@code CorsProperties} pattern from feature 10 and {@code RedisConfig} / {@code
 * RedisActiveStateProperties} from feature 8.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(DisconnectProperties.class)
public class SchedulingConfig {

  @Bean
  public TaskScheduler taskScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(2);
    scheduler.setThreadNamePrefix("chess-scheduler-");
    scheduler.setDaemon(true);
    scheduler.initialize();
    return scheduler;
  }
}
