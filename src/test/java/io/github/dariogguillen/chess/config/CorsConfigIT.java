package io.github.dariogguillen.chess.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.dariogguillen.chess.TestcontainersConfiguration;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Integration tests for the REST CORS configuration registered by {@link CorsConfig}. Boots the
 * full Spring context so the CORS interceptor sits in the real {@code DispatcherServlet} chain; a
 * {@code @WebMvcTest} slice would skip enough of the wiring that the assertions would not be
 * faithful to production. The four cases cover:
 *
 * <ul>
 *   <li>preflight from the production Cloudflare Pages origin,
 *   <li>preflight from a dev localhost origin (matches the {@code http://localhost:*} pattern),
 *   <li>preflight from a disallowed origin — the drift canary that locks in the rejection behaviour
 *       so a future config edit cannot silently widen the surface,
 *   <li>an actual {@code POST} from an allowed origin, asserting the {@code
 *       Access-Control-Allow-Origin} header is echoed on the real flight too.
 * </ul>
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class CorsConfigIT {

  private static final String CLOUDFLARE_PAGES_ORIGIN = "https://chess-frontend-52i.pages.dev";
  private static final String LOCALHOST_DEV_ORIGIN = "http://localhost:5173";
  private static final String DISALLOWED_ORIGIN = "https://evil.example";

  @Autowired private MockMvc mockMvc;

  @Test
  void preflight_allowedOriginCloudflarePages_returnsCorsHeaders() throws Exception {
    mockMvc
        .perform(
            options("/api/rooms")
                .header(HttpHeaders.ORIGIN, CLOUDFLARE_PAGES_ORIGIN)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Content-Type"))
        .andExpect(status().isOk())
        .andExpect(
            header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, CLOUDFLARE_PAGES_ORIGIN))
        .andExpect(
            header()
                .string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, Matchers.containsString("POST")))
        .andExpect(
            header()
                .string(
                    HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                    Matchers.containsString("Content-Type")))
        .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_MAX_AGE, "3600"));
  }

  @Test
  void preflight_allowedOriginLocalhost_returnsCorsHeaders() throws Exception {
    mockMvc
        .perform(
            options("/api/rooms")
                .header(HttpHeaders.ORIGIN, LOCALHOST_DEV_ORIGIN)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Content-Type"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, LOCALHOST_DEV_ORIGIN));
  }

  @Test
  void preflight_disallowedOrigin_omitsCorsHeaders() throws Exception {
    // Drift canary: Spring's CORS processor returns 403 on a disallowed preflight and does NOT
    // emit Access-Control-Allow-Origin. Asserting the absence of the header is what locks in the
    // policy — a future widening of the origin list cannot silently allow this case without
    // failing the test.
    mockMvc
        .perform(
            options("/api/rooms")
                .header(HttpHeaders.ORIGIN, DISALLOWED_ORIGIN)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Content-Type"))
        .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
  }

  @Test
  void preflight_playerIdHeader_returnsCorsHeaders() throws Exception {
    // Regression lock-in for feature 11.7: POST /api/games/{id}/moves requires the X-Player-Id
    // header (GameController.java), so the browser issues a preflight asking for it. The
    // allow-list must echo X-Player-Id back in Access-Control-Allow-Headers or the browser
    // blocks the real request. UUID.randomUUID() is a valid path placeholder — the preflight
    // hits the CORS filter only, never the controller.
    mockMvc
        .perform(
            options("/api/games/{gameId}/moves", UUID.randomUUID())
                .header(HttpHeaders.ORIGIN, CLOUDFLARE_PAGES_ORIGIN)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Content-Type, X-Player-Id"))
        .andExpect(status().isOk())
        .andExpect(
            header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, CLOUDFLARE_PAGES_ORIGIN))
        .andExpect(
            header()
                .string(
                    HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                    Matchers.containsString("X-Player-Id")));
  }

  @Test
  void actualPost_allowedOrigin_succeedsWithCorsHeader() throws Exception {
    mockMvc
        .perform(
            post("/api/rooms")
                .header(HttpHeaders.ORIGIN, CLOUDFLARE_PAGES_ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Alice\"}"))
        .andExpect(status().isCreated())
        .andExpect(
            header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, CLOUDFLARE_PAGES_ORIGIN));
  }
}
