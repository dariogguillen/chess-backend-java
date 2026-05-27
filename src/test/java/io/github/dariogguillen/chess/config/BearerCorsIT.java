package io.github.dariogguillen.chess.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.dariogguillen.chess.TestcontainersConfiguration;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Pins the CORS-preflight contract for the {@code Authorization} header introduced by feature 16
 * (auth-core). The browser will only send a cross-origin {@code Authorization: Bearer ...} after a
 * successful preflight that echoes the header in {@code Access-Control-Allow-Headers}; this test is
 * the regression canary that locks the allow-list entry in.
 *
 * <p>Companion to {@link CorsConfigIT}'s {@code preflight_playerIdHeader_returnsCorsHeaders}
 * regression — same shape, different header. Both ITs pin a header against the
 * single-source-of-truth allow-list in {@link CorsConfig#addCorsMappings} so a future widening or
 * tightening of the surface can't quietly remove either entry.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class BearerCorsIT {

  private static final String CLOUDFLARE_PAGES_ORIGIN = "https://chess-frontend-52i.pages.dev";

  @Autowired private MockMvc mockMvc;

  @Test
  void preflight_authorizationHeader_returnsCorsHeaders() throws Exception {
    mockMvc
        .perform(
            options("/api/me")
                .header(HttpHeaders.ORIGIN, CLOUDFLARE_PAGES_ORIGIN)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization"))
        .andExpect(status().isOk())
        .andExpect(
            header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, CLOUDFLARE_PAGES_ORIGIN))
        .andExpect(
            header()
                .string(
                    HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                    Matchers.containsString("Authorization")));
  }
}
