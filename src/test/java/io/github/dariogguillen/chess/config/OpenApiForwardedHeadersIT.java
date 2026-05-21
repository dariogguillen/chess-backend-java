package io.github.dariogguillen.chess.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dariogguillen.chess.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Regression coverage for the Springdoc server-URL behavior when the app runs behind a reverse
 * proxy that terminates TLS (Caddy in production).
 *
 * <p>Without {@code server.forward-headers-strategy: framework} in {@code application.yml},
 * Springdoc inspects the inner {@code localhost:8080} HTTP request and reports {@code
 * servers[0].url} as {@code http://...}. The Swagger UI page is loaded over HTTPS in production, so
 * the browser blocks "Try it out" calls to the http URL as mixed content. With the setting on,
 * Spring's {@code ForwardedHeaderFilter} consults {@code X-Forwarded-Proto} (and {@code
 * X-Forwarded-Host}) and rewrites the request URL before Springdoc reads it.
 *
 * <p>This IT pins both halves of the behavior:
 *
 * <ul>
 *   <li>With {@code X-Forwarded-Proto: https}, the reported server URL must start with {@code
 *       https://} — proves the production case is fixed.
 *   <li>Without forwarded headers, the URL falls back to {@code http://} — proves local-dev
 *       behavior is unchanged and that the test is actually exercising the header logic (drift
 *       canary; if {@code forward-headers-strategy} is removed, the first assertion fails).
 * </ul>
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiForwardedHeadersIT {

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @Test
  void apiDocs_serverUrlIsHttps_whenXForwardedProtoIsHttps() throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                get("/v3/api-docs")
                    .header("X-Forwarded-Proto", "https")
                    .header("X-Forwarded-Host", "chess-backend.duckdns.org"))
            .andExpect(status().isOk())
            .andReturn();

    String serverUrl = firstServerUrl(result.getResponse().getContentAsString());
    assertThat(serverUrl)
        .as(
            "Springdoc must honor X-Forwarded-Proto and report an https:// server URL when the app"
                + " runs behind a TLS-terminating reverse proxy. If this fails, check that"
                + " server.forward-headers-strategy=framework is set in application.yml.")
        .startsWith("https://");
  }

  @Test
  void apiDocs_serverUrlIsHttp_whenNoForwardedHeadersPresent() throws Exception {
    MvcResult result = mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn();

    String serverUrl = firstServerUrl(result.getResponse().getContentAsString());
    assertThat(serverUrl)
        .as(
            "Without X-Forwarded-Proto (local-dev / direct request), Springdoc must fall back to"
                + " the actual request scheme. This asserts the previous test isn't passing by"
                + " accident.")
        .startsWith("http://");
  }

  private String firstServerUrl(String body) throws Exception {
    JsonNode spec = objectMapper.readTree(body);
    JsonNode servers = spec.get("servers");
    if (servers == null || !servers.isArray() || servers.isEmpty()) {
      throw new AssertionError(
          "Expected /v3/api-docs body to declare a non-empty `servers` array; got: " + spec);
    }
    JsonNode urlNode = servers.get(0).get("url");
    if (urlNode == null) {
      throw new AssertionError("Expected servers[0] to have a `url` field; got: " + servers.get(0));
    }
    return urlNode.asText();
  }
}
