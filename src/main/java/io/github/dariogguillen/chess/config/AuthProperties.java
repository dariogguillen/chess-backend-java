package io.github.dariogguillen.chess.config;

import java.nio.charset.StandardCharsets;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed binding for the {@code auth.*} configuration namespace. Carries the knobs the JWT surface
 * needs ({@code auth.jwt.*}: a symmetric secret for HS256 and a token lifetime in seconds) and the
 * knobs the Google OAuth flow needs ({@code auth.oauth.*}: the frontend base URL the OAuth success
 * handler redirects to with the JWT in the URL fragment).
 *
 * <p>The compact constructors fail fast on missing or weak configuration. The HS256 algorithm
 * requires a key of at least 256 bits (32 bytes); rejecting a shorter secret at boot beats
 * discovering it at the first sign-in attempt. Likewise, a zero or negative expiry would mint
 * tokens that are already expired; we treat that as a misconfiguration and refuse to start. The
 * {@link OAuthProps} compact constructor rejects a blank {@code frontend-redirect-base} so feature
 * 18's success handler cannot accidentally emit a redirect to an empty URL — which would surface as
 * a confusing 302 to {@code /auth/callback#token=…} instead of the configured frontend.
 *
 * <p>Production sources the JWT secret from the {@code AUTH_JWT_SECRET} environment variable with
 * no default in {@code application.yml} — a missing env var fails {@code SpringApplication.run}
 * with a {@code BindException}, which is the intended fail-fast posture documented in the
 * architecture note. The OAuth frontend-redirect-base has a sensible localhost default for dev (the
 * Vite dev server on port 5173) and is overridden in production to the Cloudflare Pages URL via the
 * {@code AUTH_OAUTH_FRONTEND_REDIRECT_BASE} env var. The test profile provides deterministic values
 * for both in {@code application-test.yml} so Testcontainers-backed ITs can mint and verify tokens
 * and exercise the OAuth success handler without an environment dance.
 *
 * @param secret the HS256 shared secret, decoded as UTF-8 bytes; must be ≥ 32 bytes.
 * @param expirySeconds how long a freshly issued token stays valid; must be strictly positive.
 *     Default in {@code application.yml} is {@code 604800} = 7 days.
 * @param oauth the OAuth-related sub-namespace; non-null. See {@link OAuthProps}.
 */
@ConfigurationProperties("auth")
public record AuthProperties(Jwt jwt, OAuthProps oauth) {

  public AuthProperties {
    if (jwt == null) {
      throw new IllegalStateException("auth.jwt configuration is required");
    }
    if (oauth == null) {
      throw new IllegalStateException("auth.oauth configuration is required");
    }
  }

  /** Convenience accessor preserving the feature-16/17 API: {@code authProperties.secret()}. */
  public String secret() {
    return jwt.secret();
  }

  /**
   * Convenience accessor preserving the feature-16/17 API: {@code authProperties.expirySeconds()}.
   */
  public long expirySeconds() {
    return jwt.expirySeconds();
  }

  /**
   * Nested binding for {@code auth.jwt.*}. Carries the HS256 secret and the token lifetime in
   * seconds.
   *
   * @param secret the HS256 shared secret, decoded as UTF-8 bytes; must be ≥ 32 bytes.
   * @param expirySeconds how long a freshly issued token stays valid; must be strictly positive.
   */
  public record Jwt(String secret, long expirySeconds) {

    private static final int HS256_MIN_KEY_BYTES = 32;

    public Jwt {
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

  /**
   * Nested binding for {@code auth.oauth.*}. Carries the frontend base URL the OAuth2 success
   * handler redirects to. The success handler appends {@code /auth/callback#token=<jwt>} (or {@code
   * /auth/callback#error=<code>}) to this value, so the property must NOT include a trailing slash
   * and must NOT include the {@code /auth/callback} segment.
   *
   * @param frontendRedirectBase the frontend base URL (scheme + host + optional port), e.g. {@code
   *     http://localhost:5173} in dev or {@code https://chess-frontend-52i.pages.dev} in prod;
   *     non-blank.
   */
  public record OAuthProps(String frontendRedirectBase) {

    public OAuthProps {
      if (frontendRedirectBase == null || frontendRedirectBase.isBlank()) {
        throw new IllegalStateException("auth.oauth.frontend-redirect-base must be set");
      }
    }
  }
}
