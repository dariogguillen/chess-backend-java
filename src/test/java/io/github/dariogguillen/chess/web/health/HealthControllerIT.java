package io.github.dariogguillen.chess.web.health;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dariogguillen.chess.TestcontainersConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class HealthControllerIT {

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @Test
  void returns200WithStatusUp() throws Exception {
    mockMvc
        .perform(get("/api/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status", equalTo("UP")));
  }

  @Test
  void bodyIncludesVersionAndTimestamp() throws Exception {
    MvcResult result =
        mockMvc
            .perform(get("/api/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.version").exists())
            .andExpect(jsonPath("$.version", not(equalTo(""))))
            .andExpect(jsonPath("$.timestamp").exists())
            .andReturn();

    JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
    // timestamp must parse as ISO-8601 instant — Instant.parse throws if not.
    Instant.parse(body.get("timestamp").asText());
  }

  /**
   * Pins the {@link Clock} bean to a fixed instant and asserts that the controller's response
   * reflects it. The {@link FixedClockTestConfiguration} is loaded only by this nested class via
   * {@link Import}, so the other tests keep the real {@code Clock.systemUTC()} bean.
   */
  @Nested
  @Import(FixedClockTestConfiguration.class)
  class TimestampWithFixedClock {

    @Autowired private MockMvc mockMvc;

    @Test
    void timestampReflectsInjectedClock() throws Exception {
      mockMvc
          .perform(get("/api/health"))
          .andExpect(status().isOk())
          .andExpect(
              jsonPath(
                  "$.timestamp", equalTo(FixedClockTestConfiguration.FIXED_INSTANT.toString())));
    }
  }

  @TestConfiguration
  static class FixedClockTestConfiguration {

    static final Instant FIXED_INSTANT = Instant.parse("2026-05-14T12:00:00Z");

    @Bean
    @Primary
    Clock fixedClock() {
      return Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
    }
  }
}
