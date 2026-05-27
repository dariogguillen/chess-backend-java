package io.github.dariogguillen.chess.config.security;

import io.github.dariogguillen.chess.domain.User;
import io.github.dariogguillen.chess.persistence.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Servlet filter that translates a valid {@code Authorization: Bearer <token>} header into a
 * populated {@link org.springframework.security.core.context.SecurityContext} for the duration of
 * the request. Extends {@link OncePerRequestFilter} so it runs exactly once per request even when
 * Spring dispatches through internal forwards (the standard hook point for custom auth filters in
 * Spring Security 6).
 *
 * <p>The filter is deliberately tolerant: a missing header, a malformed token, an expired token,
 * and a bad signature all leave the chain anonymous. The authorisation decision lives in {@link
 * io.github.dariogguillen.chess.config.SecurityConfig}'s rule set — endpoints that require auth
 * surface a 401 from the access-decision step, endpoints in the anonymous allow-list keep
 * functioning. This separation keeps the failure mode uniform for the client (it sees 401 once at
 * the endpoint, never a 4xx that names the JWT failure reason) and avoids the temptation to put
 * security policy in a filter.
 *
 * <p>On a successful verify, the {@code sub} claim is parsed as a {@link UUID} and looked up via
 * {@link UserRepository}. If the user no longer exists (e.g. the account was deleted between
 * issuance and use), the filter again falls back to anonymous — a stale token is no different from
 * a forged one at this layer. When the user is found, the {@code SecurityContext} carries an
 * authenticated {@link UsernamePasswordAuthenticationToken} whose principal is the {@link User}
 * itself; controllers reach it via {@code @AuthenticationPrincipal User} or by reading {@code
 * SecurityContextHolder.getContext().getAuthentication().getPrincipal()}.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
  private static final String BEARER_PREFIX = "Bearer ";

  private final JwtVerifier verifier;
  private final UserRepository users;

  public JwtAuthenticationFilter(JwtVerifier verifier, UserRepository users) {
    this.verifier = verifier;
    this.users = users;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {

    Optional<String> bearer = extractBearer(request);
    if (bearer.isEmpty()) {
      chain.doFilter(request, response);
      return;
    }

    Optional<User> resolved = tryResolveUser(bearer.get());
    resolved.ifPresent(user -> populateSecurityContext(user, request));

    chain.doFilter(request, response);
  }

  private static Optional<String> extractBearer(HttpServletRequest request) {
    String header = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (header == null || !header.startsWith(BEARER_PREFIX)) {
      return Optional.empty();
    }
    String token = header.substring(BEARER_PREFIX.length()).trim();
    return token.isEmpty() ? Optional.empty() : Optional.of(token);
  }

  private Optional<User> tryResolveUser(String token) {
    Claims claims;
    try {
      claims = verifier.verify(token);
    } catch (JwtException ex) {
      log.warn("JWT verification failed: {}", ex.getMessage());
      return Optional.empty();
    }
    UUID userId;
    try {
      userId = UUID.fromString(claims.getSubject());
    } catch (IllegalArgumentException | NullPointerException ex) {
      log.warn("JWT subject is not a valid UUID: {}", claims.getSubject());
      return Optional.empty();
    }
    return users.findById(userId);
  }

  private static void populateSecurityContext(User user, HttpServletRequest request) {
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(user, null, List.of());
    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }
}
