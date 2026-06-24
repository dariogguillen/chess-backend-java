package io.github.dariogguillen.chess.config.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.dariogguillen.chess.TestcontainersConfiguration;
import io.github.dariogguillen.chess.config.AuthProperties;
import io.github.dariogguillen.chess.domain.User;
import io.github.dariogguillen.chess.persistence.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Integration tests for {@link OAuth2SuccessHandler} (feature 18 — auth-google-oauth).
 *
 * <p>The handler is exercised directly: we construct an {@link OAuth2AuthenticationToken} carrying
 * a {@link DefaultOAuth2User} with the Google profile attributes each case needs and call {@link
 * OAuth2SuccessHandler#onAuthenticationSuccess} with mock {@link MockHttpServletRequest} / {@link
 * MockHttpServletResponse} pair. This is deliberate: the full OAuth flow against Google's
 * authorization server is Spring Security's responsibility, not ours — running it end-to-end would
 * require WireMock-style stubbing of {@code accounts.google.com} and would test the framework, not
 * our code. The success handler is the only piece of the flow we own; testing it in isolation keeps
 * the coverage focused.
 *
 * <p>Four cases mirror the verification list in {@code progress/current.md} under "Feature 18 →
 * OAuth2SuccessHandlerIT":
 *
 * <ol>
 *   <li>fresh {@code sub} → creates a new {@link User} with {@code passwordHash = null} and
 *       redirects with the JWT in the fragment; the token is accepted by {@code /api/me}.
 *   <li>known {@code sub} → reuses the existing {@link User}; no new row created.
 *   <li>known email with a different {@code sub} → "no account linking" soft error redirect to
 *       {@code …#error=email_taken}; no new row.
 *   <li>missing email → defensive redirect to {@code …#error=oauth_missing_profile}; no row.
 * </ol>
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class OAuth2SuccessHandlerIT {

  private static final String FRONTEND_BASE = "http://localhost:5173";
  private static final String CALLBACK = "/auth/callback";

  @Autowired private OAuth2SuccessHandler handler;

  @Autowired private UserRepository users;

  @Autowired private AuthProperties authProperties;

  @Autowired private MockMvc mockMvc;

  @BeforeEach
  void cleanUsersTable() {
    users.deleteAll();
  }

  @Test
  void googleLogin_newUser_createsUserAndRedirectsWithToken() throws Exception {
    OAuth2AuthenticationToken token =
        buildOauthToken(
            Map.of(
                "sub", "google-sub-12345",
                "email", "newuser@example.com",
                "name", "New User"));

    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.onAuthenticationSuccess(request, response, token);

    assertThat(response.getStatus()).isEqualTo(302);
    String location = response.getRedirectedUrl();
    assertThat(location)
        .as("redirect location must use the configured frontend base + /auth/callback + #token=")
        .isNotNull()
        .startsWith(FRONTEND_BASE + CALLBACK + "#token=");

    String jwt = location.substring((FRONTEND_BASE + CALLBACK + "#token=").length());
    assertThat(jwt).as("JWT in fragment must not be empty").isNotBlank();

    // User row was created with passwordHash = null (NOT an empty string) and the Google sub.
    Optional<User> created = users.findByGoogleSub("google-sub-12345");
    assertThat(created)
        .as("new User must be persisted under the Google sub")
        .isPresent()
        .get()
        .satisfies(
            user -> {
              assertThat(user.getEmail()).isEqualTo("newuser@example.com");
              assertThat(user.getDisplayName()).isEqualTo("New User");
              assertThat(user.getPasswordHash())
                  .as(
                      "OAuth-only users must have a null passwordHash (NOT an empty string) so the"
                          + " email/password login path cannot accidentally authenticate them")
                  .isNull();
              assertThat(user.getGoogleSub()).isEqualTo("google-sub-12345");
              assertThat(user.getFriendCode())
                  .as("OAuth-created users must also get a friend code (feature 23.8)")
                  .isNotBlank()
                  .hasSize(8);
            });

    // The JWT must be accepted by feature 16's JwtAuthenticationFilter — call /api/me with it.
    mockMvc
        .perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email", equalTo("newuser@example.com")))
        .andExpect(jsonPath("$.displayName", equalTo("New User")));
  }

  @Test
  void googleLogin_existingUserByGoogleSub_reusesUser() throws Exception {
    User existing =
        users.save(
            new User(
                UUID.randomUUID(),
                "existing@example.com",
                "Existing User",
                null,
                "google-sub-existing",
                "FRIENDAA",
                Instant.now()));
    long countBefore = users.count();

    OAuth2AuthenticationToken token =
        buildOauthToken(
            Map.of(
                "sub", "google-sub-existing",
                "email", "existing@example.com",
                "name", "Existing User"));

    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.onAuthenticationSuccess(request, response, token);

    assertThat(response.getStatus()).isEqualTo(302);
    String location = response.getRedirectedUrl();
    assertThat(location).startsWith(FRONTEND_BASE + CALLBACK + "#token=");

    assertThat(users.count())
        .as("no new User row must be created when the googleSub already maps to a user")
        .isEqualTo(countBefore);

    String jwt = location.substring((FRONTEND_BASE + CALLBACK + "#token=").length());
    mockMvc
        .perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id", equalTo(existing.getId().toString())))
        .andExpect(jsonPath("$.email", equalTo("existing@example.com")));
  }

  @Test
  void googleLogin_existingEmailDifferentGoogleSub_doesNotMerge() throws Exception {
    // An email/password user from feature 17: passwordHash is non-null, googleSub is null.
    users.save(
        new User(
            UUID.randomUUID(),
            "taken@example.com",
            "Email Password User",
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy",
            null,
            "FRIENDBB",
            Instant.now()));
    long countBefore = users.count();

    // The OAuth flow brings a *different* sub but the same email — the "no account linking" case.
    OAuth2AuthenticationToken token =
        buildOauthToken(
            Map.of(
                "sub", "google-sub-different",
                "email", "taken@example.com",
                "name", "Pretender"));

    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.onAuthenticationSuccess(request, response, token);

    assertThat(response.getStatus()).isEqualTo(302);
    assertThat(response.getRedirectedUrl())
        .as("email collision must surface as a soft fragment-error redirect, not a 500")
        .isEqualTo(FRONTEND_BASE + CALLBACK + "#error=email_taken");

    assertThat(users.count())
        .as(
            "no new User row must be created when the email collides — the bundle's"
                + " no-account-linking decision means we do NOT merge identities")
        .isEqualTo(countBefore);

    // Pin the property the production binding reads, so the redirect target stays in lock-step
    // with the configured frontend base.
    assertThat(authProperties.oauth().frontendRedirectBase()).isEqualTo(FRONTEND_BASE);
  }

  @Test
  void googleLogin_missingEmail_redirectsWithError() throws Exception {
    long countBefore = users.count();

    // Build an OAuth2User with sub present but email missing.
    OAuth2AuthenticationToken token =
        buildOauthToken(
            Map.of(
                "sub", "google-sub-no-email",
                "name", "Anonymous"));

    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.onAuthenticationSuccess(request, response, token);

    assertThat(response.getStatus()).isEqualTo(302);
    assertThat(response.getRedirectedUrl())
        .as("missing email must surface as a defensive fragment-error redirect")
        .isEqualTo(FRONTEND_BASE + CALLBACK + "#error=oauth_missing_profile");

    assertThat(users.count())
        .as("no User row must be created when required profile attributes are missing")
        .isEqualTo(countBefore);
  }

  private static OAuth2AuthenticationToken buildOauthToken(Map<String, Object> attributes) {
    // DefaultOAuth2User requires a non-empty authorities collection. ROLE_USER is the Spring
    // Security convention for an unprivileged authenticated identity; we don't read it anywhere.
    OAuth2User principal =
        new DefaultOAuth2User(
            List.of(new SimpleGrantedAuthority("ROLE_USER")),
            attributes,
            // Name attribute key — Google uses "sub". DefaultOAuth2User.getName() reads this key.
            "sub");
    return new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "google");
  }
}
