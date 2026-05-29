package io.github.dariogguillen.chess.config;

import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * REST CORS configuration for {@code /api/**}. Reads the allowed-origin-patterns list from {@link
 * CorsProperties} so the policy stays in sync with the STOMP handshake configured in {@code
 * WebSocketConfig} — both layers consume the same source of truth.
 *
 * <p>Centralised via {@link WebMvcConfigurer#addCorsMappings} (not per-controller
 * {@code @CrossOrigin}) so the entire REST surface ships under one policy and a future tightening /
 * widening is a single edit. The reverse proxy (Caddy) does not inject CORS headers; the
 * application is the sole emitter.
 *
 * <p>Key decisions, with rationale documented in {@code notes/10-rest-cors.md}:
 *
 * <ul>
 *   <li>{@code allowedOriginPatterns} (not {@code allowedOrigins}) — required by Spring 6+ when
 *       wildcards like {@code http://localhost:*} are in play.
 *   <li>{@code allowCredentials: false} — the API is stateless JSON; identity travels in request
 *       headers and bodies, never in cookies. Flipping this on would be a deliberate policy change
 *       tied to the future auth feature, not a side effect of this one.
 *   <li>Allowed headers: {@code Content-Type, Accept, X-Player-Id, Authorization}. The first two
 *       cover JSON request/response; {@code X-Player-Id} is required by {@code POST
 *       /api/games/{id}/moves} to identify the mover; {@code Authorization} is required by feature
 *       16 (auth-core) for the {@code Authorization: Bearer <jwt>} header on every request the
 *       frontend wants to make as an authenticated user. Feature 16 lit up the JWT validator side
 *       of the auth surface; the header allow-listing keeps cross-origin preflights green for
 *       authenticated calls from the Cloudflare Pages frontend.
 *   <li>{@code maxAge: 3600} — one-hour browser-side preflight cache, a standard conservative
 *       value.
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(CorsProperties.class)
public class CorsConfig implements WebMvcConfigurer {

  private final CorsProperties props;

  public CorsConfig(CorsProperties props) {
    this.props = props;
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    registry
        .addMapping("/api/**")
        .allowedOriginPatterns(props.allowedOriginPatterns().toArray(String[]::new))
        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
        .allowedHeaders("Content-Type", "Accept", "X-Player-Id", "Authorization")
        .allowCredentials(false)
        .maxAge(3600);
  }

  /**
   * Exposes the CORS policy as a {@link CorsConfigurationSource} bean so Spring Security's filter
   * chain picks it up via {@code SecurityConfig.cors(Customizer.withDefaults())} (feature 16).
   *
   * <p>Without this bean, Spring Security's CORS configurer does NOT find a source and OPTIONS
   * preflights handled at the filter-chain level fail to emit the {@code
   * Access-Control-Allow-Origin} header — the browser then blocks the actual request. {@link
   * #addCorsMappings(CorsRegistry)} above only takes effect at the {@link
   * org.springframework.web.servlet.DispatcherServlet} level (after the security filter chain), so
   * a preflight intercepted by Spring Security before reaching the dispatcher never sees that
   * configuration.
   *
   * <p>Both this bean and {@code addCorsMappings} consume the same {@link CorsProperties}, so the
   * single-source-of-truth principle established in feature 10 is preserved: a future tightening or
   * widening of the allow-list is a single edit.
   */
  @Bean
  public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOriginPatterns(props.allowedOriginPatterns());
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
    config.setAllowedHeaders(List.of("Content-Type", "Accept", "X-Player-Id", "Authorization"));
    config.setAllowCredentials(false);
    config.setMaxAge(3600L);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/api/**", config);
    return source;
  }
}
