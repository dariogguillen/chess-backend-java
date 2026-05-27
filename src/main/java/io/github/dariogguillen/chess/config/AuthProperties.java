package io.github.dariogguillen.chess.config;

import java.nio.charset.StandardCharsets;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed binding for the {@code auth.jwt.*} configuration namespace. Carries the two knobs the JWT
 * surface needs: a symmetric secret for HS256 and a token lifetime in seconds.
 *
 * <p>The compact constructor fails fast on missing or weak secrets. The HS256 algorithm requires a
 * key of at least 256 bits (32 bytes); rejecting a shorter secret at boot beats discovering it at
 * the first sign-in attempt. Likewise, a zero or negative expiry would mint tokens that are already
 * expired; we treat that as a misconfiguration and refuse to start.
 *
 * <p>Production sources the secret from the {@code AUTH_JWT_SECRET} environment variable with no
 * default in {@code application.yml} — a missing env var fails {@code SpringApplication.run} with a
 * {@code BindException}, which is the intended fail-fast posture documented in the architecture
 * note. The test profile provides a fixed long-enough secret in {@code application-test.yml} so
 * Testcontainers-backed ITs can mint and verify tokens without an environment dance.
 *
 * @param secret the HS256 shared secret, decoded as UTF-8 bytes; must be ≥ 32 bytes.
 * @param expirySeconds how long a freshly issued token stays valid; must be strictly positive.
 *     Default in {@code application.yml} is {@code 604800} = 7 days.
 */
@ConfigurationProperties("auth.jwt")
public record AuthProperties(String secret, long expirySeconds) {

  private static final int HS256_MIN_KEY_BYTES = 32;

  public AuthProperties {
    if (secret == null || secret.isBlank()) {
      throw new IllegalStateException("auth.jwt.secret must be set");
    }
    if (secret.getBytes(StandardCharsets.UTF_8).length < HS256_MIN_KEY_BYTES) {
      throw new IllegalStateException(
          "auth.jwt.secret must be at least "
              + HS256_MIN_KEY_BYTES
              + " bytes (256 bits) for HS256");
    }
    if (expirySeconds <= 0) {
      throw new IllegalStateException(
          "auth.jwt.expiry-seconds must be strictly positive, got: " + expirySeconds);
    }
  }
}
