package io.github.dariogguillen.chess.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Top-level OpenAPI metadata for the generated spec. springdoc auto-generates the body of the spec
 * from {@code @RestController} beans and their DTOs; this bean only contributes the title,
 * description, and version that sit at the root of the document. The version is read from {@link
 * BuildProperties} when available so the spec tracks the Maven version of the running jar.
 */
@Configuration
public class OpenApiConfig {

  private static final String UNKNOWN_VERSION = "unknown";

  /**
   * Builds the root {@link OpenAPI} document. Uses {@link ObjectProvider} so the bean still
   * resolves when {@code META-INF/build-info.properties} is absent (e.g. running under {@code
   * spring-boot:test-run} without the {@code build-info} execution) — the version falls back to
   * {@code "unknown"} rather than failing context startup.
   */
  @Bean
  public OpenAPI chessBackendOpenApi(ObjectProvider<BuildProperties> buildPropertiesProvider) {
    BuildProperties buildProperties = buildPropertiesProvider.getIfAvailable();
    String version = buildProperties != null ? buildProperties.getVersion() : UNKNOWN_VERSION;
    return new OpenAPI()
        .info(
            new Info()
                .title("Chess Backend API")
                .description(
                    "REST API for the chess-backend-java project. WebSocket/STOMP "
                        + "is documented in README.md and is not covered by this spec.")
                .version(version));
  }
}
