package io.github.dariogguillen.chess.web.auth;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dariogguillen.chess.TestcontainersConfiguration;
import io.github.dariogguillen.chess.config.security.JwtIssuer;
import io.github.dariogguillen.chess.domain.User;
import io.github.dariogguillen.chess.persistence.UserRepository;
import java.time.Instant;
import java.util.UUID;
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
 * Integration tests for the feature-23.91 profile-edit surface — {@code GET /api/me} (createdAt
 * exposure), {@code PATCH /api/me} (display-name rename), and {@code PUT /api/me/password}
 * (password change). Boots the full Spring context with Testcontainers Postgres so the BCrypt
 * encoder, the JPA repository, the JWT issuer/verifier, the validator, and the security filter
 * chain all participate end-to-end. Each test wipes the {@code users} table in {@link
 * #cleanUsersTable()} so cases stay independent.
 *
 * <p>The cases mirror the verification list in {@code feature_list.json} for {@code profile-edit}.
 * The register/login MockMvc helpers follow the pattern established by {@code AuthEndpointsIT}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ProfileIT {

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @Autowired private UserRepository users;

  @Autowired private JwtIssuer jwtIssuer;

  @BeforeEach
  void cleanUsersTable() {
    users.deleteAll();
  }

  @Test
  void getMe_returnsNonNullCreatedAt() throws Exception {
    String token = registerAndGetToken("alice@example.com", "supersecret", "Alice");

    mockMvc
        .perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.createdAt", notNullValue()));
  }

  @Test
  void patchMe_rename_returns200AndPersists_leavingIdAndEmailUnchanged() throws Exception {
    MvcResult registered =
        mockMvc
            .perform(
                post("/api/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(registerJson("alice@example.com", "supersecret", "Alice")))
            .andExpect(status().isCreated())
            .andReturn();
    JsonNode body = objectMapper.readTree(registered.getResponse().getContentAsString());
    String token = body.get("token").asText();
    String originalId = body.get("user").get("id").asText();

    mockMvc
        .perform(
            patch("/api/me")
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Alice Renamed\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id", equalTo(originalId)))
        .andExpect(jsonPath("$.email", equalTo("alice@example.com")))
        .andExpect(jsonPath("$.displayName", equalTo("Alice Renamed")));

    // The change persists: a follow-up GET reflects the new name, id and email unchanged.
    mockMvc
        .perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id", equalTo(originalId)))
        .andExpect(jsonPath("$.email", equalTo("alice@example.com")))
        .andExpect(jsonPath("$.displayName", equalTo("Alice Renamed")));
  }

  @Test
  void patchMe_blankDisplayName_returns400ValidationFailed() throws Exception {
    String token = registerAndGetToken("alice@example.com", "supersecret", "Alice");

    mockMvc
        .perform(
            patch("/api/me")
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"  \"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error", equalTo("VALIDATION_FAILED")));
  }

  @Test
  void patchMe_displayNameTooLong_returns400ValidationFailed() throws Exception {
    String token = registerAndGetToken("alice@example.com", "supersecret", "Alice");
    String tooLong = "x".repeat(101);

    mockMvc
        .perform(
            patch("/api/me")
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"" + tooLong + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error", equalTo("VALIDATION_FAILED")));
  }

  @Test
  void patchMe_withoutBearerToken_returns401() throws Exception {
    mockMvc
        .perform(
            patch("/api/me")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Whoever\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void putPassword_correctCurrent_returns204_thenLoginWithNewWorks_oldFails() throws Exception {
    String token = registerAndGetToken("alice@example.com", "oldpassword", "Alice");

    mockMvc
        .perform(
            put("/api/me/password")
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(changePasswordJson("oldpassword", "newpassword")))
        .andExpect(status().isNoContent());

    // The NEW password authenticates.
    mockMvc
        .perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginJson("alice@example.com", "newpassword")))
        .andExpect(status().isOk());

    // The OLD password no longer authenticates.
    mockMvc
        .perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginJson("alice@example.com", "oldpassword")))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error", equalTo("INVALID_CREDENTIALS")));
  }

  @Test
  void putPassword_wrongCurrent_returns401InvalidCredentials() throws Exception {
    String token = registerAndGetToken("alice@example.com", "oldpassword", "Alice");

    mockMvc
        .perform(
            put("/api/me/password")
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(changePasswordJson("not-the-current", "newpassword")))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error", equalTo("INVALID_CREDENTIALS")));
  }

  @Test
  void putPassword_oauthOnlyUserWithNullHash_returns401InvalidCredentials() throws Exception {
    // An OAuth-only account has no password hash; matches() against a null hash returns false, so
    // the change attempt fails identically to a wrong current password — no leak of OAuth status.
    User oauthUser =
        new User(
            UUID.randomUUID(),
            "oauth@example.com",
            "OAuth User",
            null,
            "google-sub-123",
            uniqueFriendCode(),
            Instant.now());
    users.save(oauthUser);
    String token = jwtIssuer.issue(oauthUser);

    mockMvc
        .perform(
            put("/api/me/password")
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(changePasswordJson("anything", "newpassword")))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error", equalTo("INVALID_CREDENTIALS")));
  }

  @Test
  void putPassword_weakNewPassword_returns400ValidationFailed() throws Exception {
    String token = registerAndGetToken("alice@example.com", "oldpassword", "Alice");

    mockMvc
        .perform(
            put("/api/me/password")
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(changePasswordJson("oldpassword", "short")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error", equalTo("VALIDATION_FAILED")));
  }

  @Test
  void putPassword_withoutBearerToken_returns401() throws Exception {
    mockMvc
        .perform(
            put("/api/me/password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(changePasswordJson("whatever", "newpassword")))
        .andExpect(status().isUnauthorized());
  }

  private String registerAndGetToken(String email, String password, String displayName)
      throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(registerJson(email, password, displayName)))
            .andExpect(status().isCreated())
            .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
  }

  private static String bearer(String token) {
    return "Bearer " + token;
  }

  private static String registerJson(String email, String password, String displayName) {
    return String.format(
        "{\"email\":\"%s\",\"password\":\"%s\",\"displayName\":\"%s\"}",
        email, password, displayName);
  }

  private static String loginJson(String email, String password) {
    return String.format("{\"email\":\"%s\",\"password\":\"%s\"}", email, password);
  }

  private static String changePasswordJson(String currentPassword, String newPassword) {
    return String.format(
        "{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}", currentPassword, newPassword);
  }

  /**
   * A throwaway unique 8-char friend code for direct test inserts (prod uses FriendCodeGenerator).
   */
  private static String uniqueFriendCode() {
    return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
  }
}
