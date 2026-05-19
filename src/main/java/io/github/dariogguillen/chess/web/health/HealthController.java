package io.github.dariogguillen.chess.web.health;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes {@code GET /api/health}. The response is a small JSON document with the application
 * status, the build version and the current UTC instant. The endpoint is intentionally dedicated
 * (not routed through Spring Boot Actuator) so that the contract is owned by this controller and
 * easy to evolve.
 */
@Tag(name = "Health", description = "Application status endpoint.")
@RestController
@RequestMapping("/api/health")
public class HealthController {

  private static final String UNKNOWN_VERSION = "unknown";

  private final Clock clock;
  private final ObjectProvider<BuildProperties> buildPropertiesProvider;

  public HealthController(Clock clock, ObjectProvider<BuildProperties> buildPropertiesProvider) {
    this.clock = clock;
    this.buildPropertiesProvider = buildPropertiesProvider;
  }

  @Operation(
      summary = "Application health",
      description = "Returns the application status, build version, and current server time.")
  @ApiResponse(
      responseCode = "200",
      description = "Service is up",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = HealthResponse.class)))
  @GetMapping
  public HealthResponse health() {
    BuildProperties buildProperties = buildPropertiesProvider.getIfAvailable();
    String version = buildProperties != null ? buildProperties.getVersion() : UNKNOWN_VERSION;
    return new HealthResponse("UP", version, Instant.now(clock));
  }
}
