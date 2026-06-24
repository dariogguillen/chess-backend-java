package io.github.dariogguillen.chess.config.security;

import static org.hamcrest.Matchers.equalTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.dariogguillen.chess.TestcontainersConfiguration;
import io.github.dariogguillen.chess.config.AuthProperties;
import io.github.dariogguillen.chess.domain.User;
import io.github.dariogguillen.chess.persistence.UserRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Integration tests for the JWT-validation half of feature 16 (auth-core). Exercises {@code GET
 * /api/me} end-to-end through the real {@link io.github.dariogguillen.chess.config.SecurityConfig}
 * filter chain — boots Postgres via Testcontainers, persists a {@link User}, mints a JWT against
 * the same secret the {@link io.github.dariogguillen.chess.config.security.JwtVerifier} bean reads,
 * and asserts the round-trip.
 *
 * <p>Five cases cover the auth-foundation contract:
 *
 * <ol>
 *   <li>missing {@code Authorization} header → 401.
 *   <li>valid JWT → 200 with the expected user payload.
 *   <li>expired JWT → 401 (the parser rejects, the filter falls back to anonymous, the access
 *       decision returns 401).
 *   <li>malformed token (not even three segments) → 401, same fallback path.
 *   <li>token signed with a different secret → 401, signature verification fails.
 * </ol>
 *
 * <p>The token-minting helper duplicates the jjwt code path the future {@code JwtIssuer} (feature
 * 17) will use; keeping the helper inline here avoids prematurely extracting an issuer abstraction
 * before its consumer (the {@code AuthService}) exists.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AuthCoreIT {

  @Autowired private MockMvc mockMvc;

  @Autowired private UserRepository users;

  @Autowired private AuthProperties authProperties;

  @BeforeEach
  void cleanUsersTable() {
    users.deleteAll();
  }

  @Test
  void me_withoutAuthHeader_returns401WithAuthenticationRequiredBody() throws Exception {
    // Feature 17 swapped the placeholder HttpStatusEntryPoint for AuthEntryPoint, which writes a
    // structured ErrorResponse body with code AUTHENTICATION_REQUIRED. The other 4 cases stay
    // unchanged — the body assertion would apply equally there, but pinning it once on the
    // canonical "no credential" case is enough to lock the new behaviour without duplicating
    // assertions everywhere.
    mockMvc
        .perform(get("/api/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error", equalTo("AUTHENTICATION_REQUIRED")))
        .andExpect(jsonPath("$.message").exists())
        .andExpect(jsonPath("$.timestamp").exists());
  }

  @Test
  void me_withValidJwt_returns200WithUserPayload() throws Exception {
    User user = saveUser("alice@example.com", "Alice");
    String token = mintToken(user.getId(), user.getEmail(), 60);

    mockMvc
        .perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id", equalTo(user.getId().toString())))
        .andExpect(jsonPath("$.email", equalTo("alice@example.com")))
        .andExpect(jsonPath("$.displayName", equalTo("Alice")));
  }

  @Test
  void me_withExpiredJwt_returns401() throws Exception {
    User user = saveUser("bob@example.com", "Bob");
    // exp = iat - 1s means the token was already expired the moment it was minted.
    String expired = mintToken(user.getId(), user.getEmail(), -1);

    mockMvc
        .perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void me_withMalformedJwt_returns401() throws Exception {
    mockMvc
        .perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer not.a.jwt"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void me_withWrongSignature_returns401() throws Exception {
    User user = saveUser("carol@example.com", "Carol");
    // Sign with a key that the JwtVerifier does NOT know — must reject.
    SecretKey foreignKey =
        Keys.hmacShaKeyFor(
            "some-other-secret-of-at-least-32-bytes-aaaaaaaaaaaaaa"
                .getBytes(StandardCharsets.UTF_8));
    Instant now = Instant.now();
    String token =
        Jwts.builder()
            .subject(user.getId().toString())
            .claim("email", user.getEmail())
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusSeconds(60)))
            .signWith(foreignKey, Jwts.SIG.HS256)
            .compact();

    mockMvc
        .perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isUnauthorized());
  }

  private User saveUser(String email, String displayName) {
    User user =
        new User(
            UUID.randomUUID(), email, displayName, null, null, uniqueFriendCode(), Instant.now());
    return users.save(user);
  }

  /**
   * A throwaway unique 8-char code for direct test inserts (the prod path uses
   * FriendCodeGenerator).
   */
  private static String uniqueFriendCode() {
    return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
  }

  private String mintToken(UUID userId, String email, long ttlSeconds) {
    SecretKey key = Keys.hmacShaKeyFor(authProperties.secret().getBytes(StandardCharsets.UTF_8));
    Instant now = Instant.now();
    return Jwts.builder()
        .subject(userId.toString())
        .claim("email", email)
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plusSeconds(ttlSeconds)))
        .signWith(key, Jwts.SIG.HS256)
        .compact();
  }
}
