package io.github.dariogguillen.chess.web.auth;

import io.github.dariogguillen.chess.domain.User;
import io.github.dariogguillen.chess.exception.ErrorResponse;
import io.github.dariogguillen.chess.service.ProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoint surface for the currently authenticated user. Three routes, all guarded by the JWT
 * filter chain: {@code GET /api/me} reads the profile, {@code PATCH /api/me} renames the display
 * name (feature 23.91), and {@code PUT /api/me/password} changes the password (feature 23.91).
 *
 * <p>If the JWT-authentication filter populated the {@link
 * org.springframework.security.core.context.SecurityContext}, {@code @AuthenticationPrincipal}
 * resolves the {@link User} on each method signature. If not, Spring Security's authorisation step
 * has already returned 401 before any of these methods run — the bodies are unreachable without a
 * valid token by construction.
 *
 * <p>The read path ({@code GET}) returns the principal as-resolved. The two write paths delegate to
 * {@link ProfileService}, passing only {@code user.getId()}: the service re-loads a managed entity
 * inside its transaction rather than mutating the detached principal this controller holds.
 */
@Tag(name = "Authentication", description = "Authenticated-user information.")
@RestController
@RequestMapping("/api/me")
public class MeController {

  private final ProfileService profileService;

  public MeController(ProfileService profileService) {
    this.profileService = profileService;
  }

  @Operation(
      summary = "Get the authenticated user",
      description =
          "Returns the user identified by the Authorization: Bearer JWT. Requires a valid token "
              + "issued by this backend; the response includes the user's id, canonical email, "
              + "and display name. JWT issuance ships in feature 17; this endpoint is the "
              + "validator side of the contract.")
  @ApiResponse(
      responseCode = "200",
      description = "Authenticated user details",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = MeResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description =
          "Missing, malformed, expired, or unsigned-by-us JWT. Feature 17's AuthEntryPoint writes"
              + " a structured ErrorResponse body with code AUTHENTICATION_REQUIRED — the same"
              + " envelope every other 4xx in the API uses.",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @GetMapping
  public MeResponse me(@AuthenticationPrincipal User user) {
    return MeResponse.of(user);
  }

  @Operation(
      summary = "Update the authenticated user's display name",
      description =
          "Partial update of the authenticated user's profile. Only the display name is editable "
              + "here; email is the unique login identity (out of scope) and the password has its "
              + "own endpoint. Persists the change and returns the updated profile. The rename "
              + "reflects live in the friends list but not in past games (those snapshot the name "
              + "at archive time).")
  @ApiResponse(
      responseCode = "200",
      description = "Updated user details",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = MeResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description = "displayName is blank or longer than 100 chars (VALIDATION_FAILED)",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Missing, malformed, expired, or unsigned-by-us JWT",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @PatchMapping
  public MeResponse updateProfile(
      @AuthenticationPrincipal User user, @Valid @RequestBody UpdateProfileRequest request) {
    return profileService.updateDisplayName(user.getId(), request.displayName());
  }

  @Operation(
      summary = "Change the authenticated user's password",
      description =
          "Verifies the supplied current password against the stored hash, then sets the new "
              + "BCrypt hash. Returns 204 with no body. A wrong current password — or an OAuth-only "
              + "account that has no password set — returns 401 INVALID_CREDENTIALS, with no leak "
              + "of which case applied. Existing JWTs are not revoked (the tokens are stateless).")
  @ApiResponse(responseCode = "204", description = "Password changed; no body")
  @ApiResponse(
      responseCode = "400",
      description = "newPassword is blank or outside 8-72 chars (VALIDATION_FAILED)",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description =
          "Wrong current password, an OAuth-only account with no password, or a missing/invalid "
              + "JWT (INVALID_CREDENTIALS / authentication required)",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PutMapping("/password")
  public void changePassword(
      @AuthenticationPrincipal User user, @Valid @RequestBody ChangePasswordRequest request) {
    profileService.changePassword(user.getId(), request.currentPassword(), request.newPassword());
  }
}
