package io.github.dariogguillen.chess.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes a {@link Clock} bean so that time-dependent components can take it as a constructor
 * dependency instead of calling {@code Instant.now()} directly. Tests can override this bean to pin
 * time deterministically.
 */
@Configuration
public class ClockConfig {

  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }
}
