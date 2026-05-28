package io.github.dariogguillen.chess.config;

import io.github.dariogguillen.chess.config.security.AuthEntryPoint;
import io.github.dariogguillen.chess.config.security.JwtAuthenticationFilter;
import io.github.dariogguillen.chess.config.security.OAuth2SuccessHandler;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security base configuration for the auth bundle (features 16-20).
 *
 * <p>This is the Spring Security 6+ idiom: a {@link SecurityFilterChain} {@code @Bean} that
 * configures an injected {@link HttpSecurity} via its DSL. The deprecated {@code
 * WebSecurityConfigurerAdapter} subclass shape is removed in Spring Security 6 and is not used
 * here.
 *
 * <p>Key decisions, with rationale documented in {@code notes/16-auth-core.md} and {@code
 * notes/18-auth-google-oauth.md}:
 *
 * <ul>
 *   <li><strong>Stateless</strong> ({@link SessionCreationPolicy#STATELESS}) — no {@code
 *       HttpSession} is created or read. JWTs carry the identity on every request; the server holds
 *       no session state. This is what makes horizontal scaling cheap, and it is why CSRF
 *       protection is safely disabled (see next bullet).
 *   <li><strong>CSRF disabled</strong> — the application has no cookie-based session, so the
 *       browser does not automatically attach credentials to cross-origin requests. The CSRF attack
 *       model (forge a request from another origin and rely on the browser to attach the session
 *       cookie) does not apply when the credential is a header the script must set explicitly.
 *       {@code Authorization: Bearer ...} is exactly that header.
 *   <li><strong>CORS delegates to {@link CorsConfig}</strong> — Spring Security's CORS step picks
 *       up the registered {@code WebMvcConfigurer} mappings, so the policy stays in one place.
 *       {@code allowCredentials} remains {@code false}.
 *   <li><strong>Anonymous allow-list</strong> — the live guest play surface (POST /api/rooms, POST
 *       /api/rooms/{id}/join, GET /api/rooms/{id}, POST /api/games, GET /api/games/{id}, POST
 *       /api/games/{id}/moves, GET /api/players/{id}/games), operational endpoints (/api/health,
 *       /actuator/health, springdoc), the STOMP handshake (/ws), the OAuth flow surface (/oauth2/**
 *       for the authorization request, /login/oauth2/** for the callback — both reached BEFORE the
 *       user is authenticated; if these required auth the OAuth dance would deadlock), and all
 *       preflight OPTIONS requests stay open. {@code /api/me} is deliberately NOT on the allow-list
 *       — it is the canonical "requires auth" probe.
 *   <li><strong>{@link JwtAuthenticationFilter} placed before {@link
 *       UsernamePasswordAuthenticationFilter}</strong> — Spring Security's default filter chain
 *       reaches {@code UsernamePasswordAuthenticationFilter} as the place where form- login would
 *       populate the {@code SecurityContext}. We do not use form login, but inserting our filter at
 *       the same position is the canonical hook point recommended by the Spring Security reference
 *       docs for custom token filters.
 *   <li><strong>{@code .oauth2Login(...)} with custom success handler</strong> — feature 18 wires
 *       Spring Security's OAuth2 client DSL to delegate Google sign-in. The default success
 *       behaviour (redirect to saved-request URI or {@code /}) is replaced by {@link
 *       OAuth2SuccessHandler}, which mints a JWT via {@link
 *       io.github.dariogguillen.chess.config.security.JwtIssuer} and redirects the browser to the
 *       configured frontend with the token in the URL fragment.
 *   <li><strong>{@link BCryptPasswordEncoder} bean exposed now</strong> — feature 17 will inject it
 *       to hash passwords on register and verify them on login. Exposing it here keeps the wiring
 *       done so feature 17 is a pure-additive change.
 *   <li><strong>{@link AuthEntryPoint} returns 401 with a structured {@link
 *       io.github.dariogguillen.chess.exception.ErrorResponse} body</strong> — feature 17 swapped
 *       the {@code HttpStatusEntryPoint} feature 16 wired as a placeholder. Spring Security's
 *       default entry point for unauthenticated requests can surface 403 depending on the filter
 *       arrangement; this bean both pins the status to 401 and emits the same {@code ErrorResponse}
 *       envelope that {@code GlobalExceptionHandler} produces for the rest of the 4xx surface, with
 *       code {@code AUTHENTICATION_REQUIRED}.
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfig {

  private final JwtAuthenticationFilter jwtAuthenticationFilter;
  private final AuthEntryPoint authEntryPoint;
  private final OAuth2SuccessHandler oAuth2SuccessHandler;

  public SecurityConfig(
      JwtAuthenticationFilter jwtAuthenticationFilter,
      AuthEntryPoint authEntryPoint,
      OAuth2SuccessHandler oAuth2SuccessHandler) {
    this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    this.authEntryPoint = authEntryPoint;
    this.oAuth2SuccessHandler = oAuth2SuccessHandler;
  }

  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    return http.csrf(csrf -> csrf.disable())
        .cors(Customizer.withDefaults())
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(HttpMethod.OPTIONS, "/**")
                    .permitAll()
                    // Operational / docs surface.
                    .requestMatchers(
                        "/api/health",
                        "/actuator/**",
                        "/v3/api-docs",
                        "/v3/api-docs/**",
                        "/swagger-ui",
                        "/swagger-ui/**",
                        "/swagger-ui.html",
                        "/ws/**")
                    .permitAll()
                    // Live guest-play surface — features 4, 5, 6, 9 stay anonymous.
                    .requestMatchers(HttpMethod.POST, "/api/rooms", "/api/rooms/*/join")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/rooms/*")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/games", "/api/games/*/moves")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/games/*", "/api/players/*/games")
                    .permitAll()
                    // Auth issuance endpoints (feature 17): how users acquire a JWT in the first
                    // place — must be reachable without one.
                    .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login")
                    .permitAll()
                    // OAuth 2.0 client endpoints (feature 18): /oauth2/authorization/{provider}
                    // initiates the flow, /login/oauth2/code/{provider} is the callback. Both run
                    // BEFORE the user is authenticated; requiring auth here would deadlock the
                    // OAuth dance.
                    .requestMatchers("/oauth2/**", "/login/oauth2/**")
                    .permitAll()
                    // Everything else (today: /api/me) requires authentication.
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(eh -> eh.authenticationEntryPoint(authEntryPoint))
        .oauth2Login(oauth -> oauth.successHandler(oAuth2SuccessHandler))
        .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
        .build();
  }

  /**
   * BCrypt encoder exposed as a bean so feature 17's {@code AuthService} can inject it without
   * declaring its own. The strength parameter defaults to 10 (Spring Security default), a
   * deliberate trade-off between hash time (~100ms on a modern x86) and resistance to offline
   * attack. Increase to 12+ when the user count justifies the per-login CPU cost.
   */
  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }
}
