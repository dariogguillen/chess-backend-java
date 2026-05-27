package io.github.dariogguillen.chess.config.security;

import io.github.dariogguillen.chess.config.AuthProperties;
import io.github.dariogguillen.chess.domain.User;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

/**
 * Issuance counterpart to {@link JwtVerifier}. Mints HS256-signed JWTs whose shape matches what the
 * verifier accepts, byte-for-byte: claims are {@code sub} = {@link User#getId()} as a UUID string,
 * {@code email} = {@link User#getEmail()}, {@code iat} = the current {@link Instant} from the
 * injected {@link Clock}, and {@code exp} = {@code iat + AuthProperties.expirySeconds()}.
 *
 * <p>The key derivation is identical to {@link JwtVerifier}'s — both classes read {@link
 * AuthProperties#secret()}, decode UTF-8 bytes, and call {@link Keys#hmacShaKeyFor(byte[])}.
 * Because the derivation is pure (same input, same library, same byte interpretation), the issued
 * tokens round-trip through the verifier without any coordination beyond sharing the same {@link
 * AuthProperties} bean. Choosing identical-derivation over a shared-{@link SecretKey} bean keeps
 * each class self-contained — the verifier was wired by feature 16 without an issuer in mind, and
 * inverting that dependency now would require touching the verifier to no real benefit.
 *
 * <p>The {@link Clock} comes from the application's {@code ClockConfig} bean (UTC system clock in
 * production; tests can swap a {@code Clock.fixed(...)} via a {@code @Primary} {@code Clock} bean
 * as {@code HealthControllerIT} does). The expired-token IT in {@code AuthCoreIT} mints its tokens
 * with the jjwt API directly rather than through this bean, so swapping {@link Clock} here does not
 * affect the verifier-side regression — only the issuer-side timestamps move with it.
 */
@Component
public class JwtIssuer {

  private final SecretKey key;
  private final long expirySeconds;
  private final Clock clock;

  public JwtIssuer(AuthProperties props, Clock clock) {
    this.key = Keys.hmacShaKeyFor(props.secret().getBytes(StandardCharsets.UTF_8));
    this.expirySeconds = props.expirySeconds();
    this.clock = clock;
  }

  /**
   * Issues a freshly signed JWT for the given user. The claim shape is locked by the auth bundle's
   * bundle-level decisions (see {@code progress/current.md} → "Bundle-level technical decisions"):
   * {@code sub}, {@code email}, {@code iat}, {@code exp}. No roles or authorities are emitted — the
   * only role today is "authenticated user".
   *
   * @param user the user the token represents; non-null. Caller is responsible for ensuring the
   *     user has already been persisted (so the {@code sub} claim resolves on the verifier side).
   * @return a serialised JWT string suitable for use in an {@code Authorization: Bearer ...}
   *     header.
   */
  public String issue(User user) {
    Instant now = Instant.now(clock);
    Instant expiry = now.plusSeconds(expirySeconds);
    return Jwts.builder()
        .subject(user.getId().toString())
        .claim("email", user.getEmail())
        .issuedAt(Date.from(now))
        .expiration(Date.from(expiry))
        .signWith(key, Jwts.SIG.HS256)
        .compact();
  }
}
