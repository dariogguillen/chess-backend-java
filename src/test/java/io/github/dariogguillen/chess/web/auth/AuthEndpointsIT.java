package io.github.dariogguillen.chess.web.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dariogguillen.chess.TestcontainersConfiguration;
import io.github.dariogguillen.chess.persistence.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Integration tests for the feature-17 auth endpoints — {@code POST /api/auth/register} and {@code
 * POST /api/auth/login}. Boots the full Spring context with Testcontainers Postgres so the BCrypt
 * encoder, the JPA repository, the JWT issuer, the validator, and the security filter chain all
 * participate end-to-end. Each test wipes the {@code users} table in {@link #cleanUsersTable()} so
 * cases stay independent.
 *
 * <p>The nine cases mirror the verification list in {@code progress/current.md} under "Feature 17 →
 * Verification".
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AuthEndpointsIT {

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @Autowired private UserRepository users;

  @BeforeEach
  void cleanUsersTable() {
    users.deleteAll();
  }

  @Test
  void register_validInput_returns201WithTokenAndUser() throws Exception {
    mockMvc
        .perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerJson("alice@example.com", "supersecret", "Alice")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.token", notNullValue()))
        // JWT canonical shape: three base64url-encoded segments separated by dots.
        .andExpect(
            jsonPath(
                "$.token",
                matchesPattern("^[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+$")))
        .andExpect(jsonPath("$.user.id", notNullValue()))
        .andExpect(jsonPath("$.user.email", equalTo("alice@example.com")))
        .andExpect(jsonPath("$.user.displayName", equalTo("Alice")));
  }

  @Test
  void register_duplicateEmail_returns409WithEmailAlreadyTakenCode() throws Exception {
    register("alice@example.com", "supersecret", "Alice");

    mockMvc
        .perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerJson("alice@example.com", "anotherpw", "AliceTwo")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error", equalTo("EMAIL_ALREADY_TAKEN")))
        .andExpect(jsonPath("$.message").exists());
  }

  @Test
  void register_invalidEmailFormat_returns400ValidationFailed() throws Exception {
    mockMvc
        .perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerJson("not-an-email", "supersecret", "Alice")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error", equalTo("VALIDATION_FAILED")));
  }

  @Test
  void register_passwordTooShort_returns400ValidationFailed() throws Exception {
    mockMvc
        .perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerJson("alice@example.com", "short", "Alice")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error", equalTo("VALIDATION_FAILED")));
  }

  @Test
  void register_missingDisplayName_returns400ValidationFailed() throws Exception {
    mockMvc
        .perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerJson("alice@example.com", "supersecret", "")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error", equalTo("VALIDATION_FAILED")));
  }

  @Test
  void login_validCredentials_returns200WithToken() throws Exception {
    register("alice@example.com", "supersecret", "Alice");

    mockMvc
        .perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginJson("alice@example.com", "supersecret")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.token", notNullValue()))
        .andExpect(jsonPath("$.user.email", equalTo("alice@example.com")))
        .andExpect(jsonPath("$.user.displayName", equalTo("Alice")));
  }

  @Test
  void login_wrongPassword_returns401InvalidCredentials() throws Exception {
    register("alice@example.com", "supersecret", "Alice");

    mockMvc
        .perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginJson("alice@example.com", "totally-wrong")))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error", equalTo("INVALID_CREDENTIALS")))
        .andExpect(jsonPath("$.message", equalTo("Invalid email or password")));
  }

  @Test
  void login_unknownEmail_returnsSameBodyAsWrongPassword() throws Exception {
    register("alice@example.com", "supersecret", "Alice");

    MvcResult wrongPassword =
        mockMvc
            .perform(
                post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(loginJson("alice@example.com", "totally-wrong")))
            .andExpect(status().isUnauthorized())
            .andReturn();

    MvcResult unknownEmail =
        mockMvc
            .perform(
                post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(loginJson("nobody@example.com", "totally-wrong")))
            .andExpect(status().isUnauthorized())
            .andReturn();

    // Compare body field by field — everything must match except the timestamp.
    JsonNode wrongPwBody = objectMapper.readTree(wrongPassword.getResponse().getContentAsString());
    JsonNode unknownEmailBody =
        objectMapper.readTree(unknownEmail.getResponse().getContentAsString());

    assertThat(unknownEmailBody.get("error").asText())
        .as("unknown-email error code must match wrong-password to prevent user enumeration")
        .isEqualTo(wrongPwBody.get("error").asText())
        .isEqualTo("INVALID_CREDENTIALS");
    assertThat(unknownEmailBody.get("message").asText())
        .as("unknown-email message must match wrong-password to prevent user enumeration")
        .isEqualTo(wrongPwBody.get("message").asText())
        .isEqualTo("Invalid email or password");
    // We do not assert response body equality outright because the timestamp legitimately
    // differs between the two calls; the assertion above pins everything else.
  }

  @Test
  void roundTrip_registerThenLogin_thenMeReturnsSameUser() throws Exception {
    MvcResult registerResult =
        mockMvc
            .perform(
                post("/api/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(registerJson("alice@example.com", "supersecret", "Alice")))
            .andExpect(status().isCreated())
            .andReturn();
    JsonNode registerBody =
        objectMapper.readTree(registerResult.getResponse().getContentAsString());
    String userIdFromRegister = registerBody.get("user").get("id").asText();

    // Now log in and prove the JWT minted by login is accepted by feature 16's
    // JwtAuthenticationFilter.
    MvcResult loginResult =
        mockMvc
            .perform(
                post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(loginJson("alice@example.com", "supersecret")))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode loginBody = objectMapper.readTree(loginResult.getResponse().getContentAsString());
    String loginToken = loginBody.get("token").asText();
    String userIdFromLogin = loginBody.get("user").get("id").asText();

    assertThat(userIdFromLogin)
        .as("register and login must return the same user id for the same account")
        .isEqualTo(userIdFromRegister);

    mockMvc
        .perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + loginToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id", equalTo(userIdFromRegister)))
        .andExpect(jsonPath("$.email", equalTo("alice@example.com")))
        .andExpect(jsonPath("$.displayName", equalTo("Alice")));
  }

  private void register(String email, String password, String displayName) throws Exception {
    mockMvc
        .perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerJson(email, password, displayName)))
        .andExpect(status().isCreated());
  }

  private static String registerJson(String email, String password, String displayName) {
    return String.format(
        "{\"email\":\"%s\",\"password\":\"%s\",\"displayName\":\"%s\"}",
        email, password, displayName);
  }

  private static String loginJson(String email, String password) {
    return String.format("{\"email\":\"%s\",\"password\":\"%s\"}", email, password);
  }
}
