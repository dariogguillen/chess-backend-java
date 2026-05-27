package io.github.dariogguillen.chess.config.security;

import io.github.dariogguillen.chess.config.AuthProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

/**
 * Thin wrapper over the jjwt library that exposes a single {@link #verify(String)} method returning
 * the decoded {@link Claims}. Used today by {@link JwtAuthenticationFilter} on every incoming
 * request that carries an {@code Authorization: Bearer ...} header; will be re-used by feature 17's
 * {@code JwtIssuer} symmetrically (same secret, same algorithm, same parser) so the issuer and the
 * verifier cannot drift out of agreement.
 *
 * <p>The HMAC secret is loaded once at bean-construction time from {@link AuthProperties#secret()}
 * and stored as an immutable {@link SecretKey} — re-deriving the key on every request would burn
 * CPU for no benefit, and the secret is fixed for the JVM's lifetime. {@link Keys#hmacShaKeyFor}
 * enforces the 256-bit minimum at decoding time as a second line of defence behind {@link
 * AuthProperties}'s constructor check.
 *
 * <p>{@link #verify(String)} throws {@link JwtException} (or a subclass) on every failure mode the
 * jjwt parser reports: bad signature, expired, malformed, unsupported algorithm. The caller decides
 * what to do — {@link JwtAuthenticationFilter} treats every failure as "leave the chain anonymous
 * and let Spring Security's authorization rules return 401 if the endpoint requires auth", which
 * keeps the filter's failure mode uniform and avoids leaking the specific reason in the response.
 */
@Component
public class JwtVerifier {

  private final SecretKey key;

  public JwtVerifier(AuthProperties props) {
    this.key = Keys.hmacShaKeyFor(props.secret().getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Parses and validates a JWT against the configured HS256 secret and expiry. Returns the decoded
   * {@link Claims} payload on success; throws {@link JwtException} (or a subclass — {@code
   * ExpiredJwtException}, {@code SignatureException}, {@code MalformedJwtException}, {@code
   * UnsupportedJwtException}) on any failure.
   *
   * @param token the bare JWT (no {@code Bearer } prefix); non-null.
   * @return the parsed claims, including {@code sub}, {@code email}, {@code iat}, {@code exp}.
   * @throws JwtException if the token is missing, expired, malformed, or fails signature
   *     verification.
   */
  public Claims verify(String token) {
    return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
  }
}
