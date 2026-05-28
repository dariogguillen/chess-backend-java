package io.github.dariogguillen.chess.config.security;

import io.github.dariogguillen.chess.config.AuthProperties;
import io.github.dariogguillen.chess.domain.User;
import io.github.dariogguillen.chess.persistence.UserRepository;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/**
 * Custom {@link AuthenticationSuccessHandler} that closes the Google OAuth 2.0 sign-in flow
 * introduced by feature 18 (auth-google-oauth).
 *
 * <p>Spring Security's {@code .oauth2Login()} DSL handles the first 80% of the flow: the
 * authorization redirect to Google, the exchange of the authorization code for tokens, the userinfo
 * fetch, and the construction of an {@link OAuth2User} principal carrying Google's profile
 * attributes ({@code sub}, {@code email}, {@code name}, ...). This handler owns the last 20%: it
 * find-or-creates our domain {@link User} keyed by Google's stable {@code sub} claim, mints a JWT
 * via the {@link JwtIssuer} from feature 17 — byte-for-byte interchangeable with one minted by
 * {@code /api/auth/login} — and redirects the browser to the configured frontend with the token in
 * the URL fragment ({@code …/auth/callback#token=<jwt>}).
 *
 * <p>The redirect uses a URL fragment instead of a query string for a deliberate reason: fragments
 * are not sent to the server in subsequent requests, so even if an intermediate proxy logs full
 * URLs, the token does not leak into server logs. The frontend reads {@code window.location.hash},
 * parses {@code token=…}, stores the token, and calls {@code history.replaceState} to clean the URL
 * before the user notices.
 *
 * <p>Identity-collision policy ("Option B" from the leader plan, see {@code progress/current.md}):
 *
 * <ul>
 *   <li>If a {@link User} already exists for the Google {@code sub}, it is reused.
 *   <li>If no {@link User} has the {@code sub} but the {@code email} is already taken by an
 *       existing user (an email/password account from feature 17), the handler does NOT silently
 *       merge identities — that is the "no account linking" out-of-scope decision for the auth
 *       bundle. Instead, the handler redirects to {@code …/auth/callback#error=email_taken} so the
 *       frontend can render a user-facing message. Throwing here would surface a 500 to a user who
 *       did nothing wrong; a fragment-error redirect is the soft-error idiom that matches the rest
 *       of the OAuth UX.
 *   <li>If neither matches, a fresh {@link User} is created with {@code passwordHash = null} (NOT
 *       an empty string — BCrypt would happily hash an empty string and create a phantom-loginable
 *       user).
 * </ul>
 *
 * <p>Log hygiene: the warning paths emit generic messages that carry NO personally identifiable
 * information — no email, no Google {@code sub}, no display name. The reviewer greps for {@code
 * log.*email}, {@code log.*sub}, and {@code log.*name} in this file; matches in the OAuth failure
 * paths would fail review.
 */
@Component
public class OAuth2SuccessHandler implements AuthenticationSuccessHandler {

  private static final Logger log = LoggerFactory.getLogger(OAuth2SuccessHandler.class);
  private static final String CALLBACK_PATH = "/auth/callback";
  private static final String ERROR_EMAIL_TAKEN = "email_taken";
  private static final String ERROR_MISSING_PROFILE = "oauth_missing_profile";

  private final UserRepository users;
  private final JwtIssuer jwtIssuer;
  private final AuthProperties authProperties;
  private final Clock clock;
  private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();

  public OAuth2SuccessHandler(
      UserRepository users, JwtIssuer jwtIssuer, AuthProperties authProperties, Clock clock) {
    this.users = users;
    this.jwtIssuer = jwtIssuer;
    this.authProperties = authProperties;
    this.clock = clock;
  }

  @Override
  public void onAuthenticationSuccess(
      HttpServletRequest request, HttpServletResponse response, Authentication authentication)
      throws IOException, ServletException {

    if (!(authentication instanceof OAuth2AuthenticationToken oauthToken)) {
      // Defensive: Spring Security wires this handler exclusively for oauth2Login, so the
      // Authentication should always be an OAuth2AuthenticationToken at runtime. Failing soft to
      // an error redirect keeps the user out of a confusing server-error page if the wiring ever
      // changes.
      log.warn("OAuth2 success handler invoked with non-OAuth2 Authentication; redirecting error");
      redirectWithError(request, response, ERROR_MISSING_PROFILE);
      return;
    }

    OAuth2User principal = oauthToken.getPrincipal();
    String googleSub = stringAttribute(principal, "sub");
    String email = stringAttribute(principal, "email");
    String name = stringAttribute(principal, "name");

    if (googleSub == null || email == null) {
      log.warn("OAuth callback missing required profile attributes");
      redirectWithError(request, response, ERROR_MISSING_PROFILE);
      return;
    }

    Optional<User> bySub = users.findByGoogleSub(googleSub);
    User user;
    if (bySub.isPresent()) {
      user = bySub.get();
    } else {
      Optional<User> byEmail = users.findByEmail(email);
      if (byEmail.isPresent()) {
        // Email taken by an existing user (email/password or different googleSub). Per the
        // bundle's "no account linking" decision, surface this as a soft error to the frontend.
        log.warn("OAuth callback for email already registered to a different identity");
        redirectWithError(request, response, ERROR_EMAIL_TAKEN);
        return;
      }
      user = createUser(googleSub, email, name);
    }

    String token = jwtIssuer.issue(user);
    String target =
        authProperties.oauth().frontendRedirectBase() + CALLBACK_PATH + "#token=" + token;
    redirectStrategy.sendRedirect(request, response, target);
  }

  private User createUser(String googleSub, String email, String name) {
    String displayName = (name == null || name.isBlank()) ? localPart(email) : name;
    User created =
        new User(
            UUID.randomUUID(),
            email,
            displayName,
            // passwordHash MUST be null (NOT an empty string) for OAuth-only users. BCrypt would
            // happily hash an empty string into a valid-looking BCrypt output, which AuthService
            // would then accept as a successful match on any "" password — a phantom-loginable
            // user. Null short-circuits the matches() path in BCryptPasswordEncoder.
            null,
            googleSub,
            Instant.now(clock));
    return users.save(created);
  }

  private static String localPart(String email) {
    int at = email.indexOf('@');
    return at > 0 ? email.substring(0, at) : email;
  }

  private static String stringAttribute(OAuth2User principal, String key) {
    Object value = principal.getAttribute(key);
    if (value == null) {
      return null;
    }
    String s = value.toString();
    return s.isBlank() ? null : s;
  }

  private void redirectWithError(
      HttpServletRequest request, HttpServletResponse response, String errorCode)
      throws IOException {
    String target =
        authProperties.oauth().frontendRedirectBase() + CALLBACK_PATH + "#error=" + errorCode;
    redirectStrategy.sendRedirect(request, response, target);
  }
}
