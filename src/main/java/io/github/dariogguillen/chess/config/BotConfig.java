package io.github.dariogguillen.chess.config;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for the bot-opponent feature (feature 23, {@code bot-opponent}): the dedicated executor on
 * which a Fairy-Stockfish subprocess thinks, plus the {@link BotProperties} binding.
 *
 * <p><strong>Why a separate executor and not the clock {@link
 * org.springframework.scheduling.TaskScheduler}.</strong> The clock {@code TaskScheduler} ({@code
 * SchedulingConfig}) is a 2-thread pool sized for short, non-blocking flag-timer tasks. A bot move
 * blocks while the subprocess runs its depth-bounded search (feature 23.7); running it on the
 * scheduler would starve the flag timers and risk a timed game not flagging on time. The bot
 * therefore gets its own small fixed pool. The threads are daemon so an in-flight search never
 * holds the JVM alive during shutdown, and named for log readability.
 *
 * <p>The pool is shut down gracefully in {@link #shutdown()} ({@code @PreDestroy}): no new tasks
 * are accepted, in-flight searches get a brief window to finish, and anything still running is
 * interrupted. The subprocess itself is always force-killed in the engine adapter's own {@code
 * finally}, so even an abrupt shutdown cannot leak a Fairy-Stockfish process.
 */
@Configuration
@EnableConfigurationProperties(BotProperties.class)
public class BotConfig {

  private static final Logger log = LoggerFactory.getLogger(BotConfig.class);

  /**
   * Bean name for the dedicated bot executor, so {@code BotMoveService} can {@code @Qualifier} it.
   */
  public static final String BOT_EXECUTOR = "botExecutor";

  /** How long {@link #shutdown()} waits for in-flight searches before interrupting them. */
  private static final long SHUTDOWN_GRACE_SECONDS = 2L;

  private ExecutorService botExecutor;

  @Bean(BOT_EXECUTOR)
  public ExecutorService botExecutor(BotProperties properties) {
    ThreadFactory threadFactory =
        new ThreadFactory() {
          private final AtomicInteger counter = new AtomicInteger(1);

          @Override
          public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "chess-bot-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
          }
        };
    this.botExecutor = Executors.newFixedThreadPool(properties.poolSize(), threadFactory);
    return this.botExecutor;
  }

  @PreDestroy
  public void shutdown() {
    if (botExecutor == null) {
      return;
    }
    botExecutor.shutdown();
    try {
      if (!botExecutor.awaitTermination(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)) {
        log.warn("Bot executor did not terminate within {}s; interrupting", SHUTDOWN_GRACE_SECONDS);
        botExecutor.shutdownNow();
      }
    } catch (InterruptedException ex) {
      botExecutor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}
