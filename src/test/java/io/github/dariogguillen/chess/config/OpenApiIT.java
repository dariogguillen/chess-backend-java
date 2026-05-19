package io.github.dariogguillen.chess.config;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dariogguillen.chess.TestcontainersConfiguration;
import java.util.Iterator;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Integration tests for the springdoc-generated OpenAPI spec at {@code /v3/api-docs} and the
 * Swagger UI at {@code /swagger-ui.html}. Boots the full Spring context so springdoc actually scans
 * the controllers; the assertions here are what protect the convention codified in {@code
 * docs/conventions.md → "API documentation"} and {@code CHECKPOINTS.md}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiIT {

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @Test
  void apiDocsEndpoint_returnsValidOpenApiSpec() throws Exception {
    MvcResult result = mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn();

    JsonNode spec = objectMapper.readTree(result.getResponse().getContentAsString());

    JsonNode paths = spec.get("paths");
    assertThatPathExists(paths, "/api/health");
    assertThatPathExists(paths, "/api/rooms");
    assertThatPathExists(paths, "/api/rooms/{id}/join");

    JsonNode schemas = spec.path("components").path("schemas");
    if (!schemas.has("ErrorResponse")) {
      throw new AssertionError(
          "Expected components.schemas.ErrorResponse in the OpenAPI spec, got: " + schemas);
    }

    // Every 4xx on the room endpoints must reference ErrorResponse, not inline a schema.
    assertFourXxReferencesErrorResponse(paths, "/api/rooms", "post");
    assertFourXxReferencesErrorResponse(paths, "/api/rooms/{id}/join", "post");
  }

  @Test
  void swaggerUi_returnsHtmlPage() throws Exception {
    // springdoc serves /swagger-ui.html as a redirect to /swagger-ui/index.html.
    mockMvc
        .perform(get("/swagger-ui.html"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/swagger-ui/index.html"));

    mockMvc
        .perform(get("/swagger-ui/index.html"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", containsString("text/html")));
  }

  @Test
  void apiDocs_includesOperationSummaries() throws Exception {
    MvcResult result = mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn();

    JsonNode spec = objectMapper.readTree(result.getResponse().getContentAsString());
    JsonNode paths = spec.get("paths");

    Iterator<Map.Entry<String, JsonNode>> pathEntries = paths.fields();
    while (pathEntries.hasNext()) {
      Map.Entry<String, JsonNode> pathEntry = pathEntries.next();
      String pathKey = pathEntry.getKey();
      Iterator<Map.Entry<String, JsonNode>> methodEntries = pathEntry.getValue().fields();
      while (methodEntries.hasNext()) {
        Map.Entry<String, JsonNode> methodEntry = methodEntries.next();
        String methodKey = methodEntry.getKey();
        JsonNode summaryNode = methodEntry.getValue().get("summary");
        if (summaryNode == null || summaryNode.asText().isEmpty()) {
          throw new AssertionError(
              "Missing or empty @Operation(summary = ...) for "
                  + methodKey.toUpperCase()
                  + " "
                  + pathKey
                  + ". Every endpoint must declare a non-empty summary.");
        }
      }
    }
  }

  private static void assertThatPathExists(JsonNode paths, String path) {
    if (paths == null || !paths.has(path)) {
      throw new AssertionError(
          "Expected OpenAPI spec to contain path \""
              + path
              + "\"; got keys: "
              + (paths == null ? "<no paths>" : paths.fieldNames().toString()));
    }
  }

  private static void assertFourXxReferencesErrorResponse(
      JsonNode paths, String path, String method) {
    JsonNode responses = paths.path(path).path(method).path("responses");
    Iterator<Map.Entry<String, JsonNode>> responseEntries = responses.fields();
    boolean sawAny4xx = false;
    while (responseEntries.hasNext()) {
      Map.Entry<String, JsonNode> entry = responseEntries.next();
      String status = entry.getKey();
      if (status.startsWith("4")) {
        sawAny4xx = true;
        JsonNode ref =
            entry.getValue().path("content").path("application/json").path("schema").path("$ref");
        String refValue = ref.asText("");
        if (!refValue.equals("#/components/schemas/ErrorResponse")) {
          throw new AssertionError(
              "Expected "
                  + method.toUpperCase()
                  + " "
                  + path
                  + " response "
                  + status
                  + " to reference #/components/schemas/ErrorResponse, got: "
                  + (refValue.isEmpty() ? "<no $ref>" : refValue));
        }
      }
    }
    if (!sawAny4xx) {
      throw new AssertionError(
          "Expected at least one 4xx response on "
              + method.toUpperCase()
              + " "
              + path
              + ", got: "
              + responses);
    }
  }
}
