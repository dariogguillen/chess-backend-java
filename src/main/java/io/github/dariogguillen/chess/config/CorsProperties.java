package io.github.dariogguillen.chess.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed binding for the {@code chess.cors.*} configuration namespace. Holds the single
 * allowed-origin-patterns list that both the REST CORS filter (in {@code CorsConfig}) and the
 * STOMP-over-WebSocket handshake (in {@code WebSocketConfig}) read from. Extracted to a property
 * class so the two layers cannot drift: the next time a new origin needs to be added (e.g. a Vercel
 * preview deploy), the change happens in {@code application.yml} or via the {@code
 * CHESS_CORS_ALLOWED_ORIGIN_PATTERNS} env var, and both layers pick it up.
 *
 * <p>Spring binds a comma-separated env-var string to {@code List<String>} natively; no custom
 * converter is required. The compact constructor copies the incoming list into an immutable view
 * and rejects empty / null input — a CORS-aware deployment without any allowed origin is a
 * misconfiguration we want to fail fast on rather than silently lock everyone out at preflight
 * time.
 *
 * @param allowedOriginPatterns the origin patterns accepted by both the REST {@code /api/**} filter
 *     and the STOMP {@code /ws} handshake. Patterns follow Spring's {@code
 *     setAllowedOriginPatterns} alphabet (full origin or {@code scheme://host:*} wildcard).
 */
@ConfigurationProperties("chess.cors")
public record CorsProperties(List<String> allowedOriginPatterns) {

  public CorsProperties {
    if (allowedOriginPatterns == null || allowedOriginPatterns.isEmpty()) {
      throw new IllegalStateException("chess.cors.allowed-origin-patterns must be set");
    }
    allowedOriginPatterns = List.copyOf(allowedOriginPatterns);
  }
}
