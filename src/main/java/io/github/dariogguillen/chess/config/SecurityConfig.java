package io.github.dariogguillen.chess.config;

import io.github.dariogguillen.chess.config.security.JwtAuthenticationFilter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security base configuration for the auth bundle (features 16-20).
 *
 * <p>This is the Spring Security 6+ idiom: a {@link SecurityFilterChain} {@code @Bean} that
 * configures an injected {@link HttpSecurity} via its DSL. The deprecated {@code
 * WebSecurityConfigurerAdapter} subclass shape is removed in Spring Security 6 and is not used
 * here.
 *
 * <p>Key decisions, with rationale documented in {@code notes/16-auth-core.md}:
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
 *       /actuator/health, springdoc), the STOMP handshake (/ws), and all preflight OPTIONS requests
 *       stay open. {@code /api/me} is deliberately NOT on the allow-list — it is the canonical
 *       "requires auth" probe.
 *   <li><strong>{@link JwtAuthenticationFilter} placed before {@link
 *       UsernamePasswordAuthenticationFilter}</strong> — Spring Security's default filter chain
 *       reaches {@code UsernamePasswordAuthenticationFilter} as the place where form- login would
 *       populate the {@code SecurityContext}. We do not use form login, but inserting our filter at
 *       the same position is the canonical hook point recommended by the Spring Security reference
 *       docs for custom token filters.
 *   <li><strong>{@link BCryptPasswordEncoder} bean exposed now</strong> — feature 17 will inject it
 *       to hash passwords on register and verify them on login. Exposing it here keeps the wiring
 *       done so feature 17 is a pure-additive change.
 *   <li><strong>{@link HttpStatusEntryPoint} returns 401</strong> — Spring Security's default entry
 *       point for unauthenticated requests can surface 403 depending on the filter arrangement. We
 *       override to {@code 401 Unauthorized} for the "missing or invalid credentials" case so the
 *       contract matches the acceptance criteria.
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfig {

  private final JwtAuthenticationFilter jwtAuthenticationFilter;

  public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
    this.jwtAuthenticationFilter = jwtAuthenticationFilter;
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
                    // Everything else (today: /api/me) requires authentication.
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            eh -> eh.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
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
