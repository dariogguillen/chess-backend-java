package io.github.dariogguillen.chess.web.auth;

import io.github.dariogguillen.chess.domain.User;
import io.github.dariogguillen.chess.exception.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoint exposing the currently authenticated user. Today this is the only authenticated
 * route in the application — its purpose is twofold: a probe the frontend uses to confirm a stored
 * JWT is still valid, and a regression-locking surface for the auth filter chain.
 *
 * <p>If the JWT-authentication filter populated the {@link
 * org.springframework.security.core.context.SecurityContext}, {@code @AuthenticationPrincipal}
 * resolves the {@link User} on the method signature. If not, Spring Security's authorisation step
 * has already returned 401 before this controller method runs — the body of {@link #me(User)} is
 * unreachable without a valid token by construction.
 */
@Tag(name = "Authentication", description = "Authenticated-user information.")
@RestController
@RequestMapping("/api/me")
public class MeController {

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
    return new MeResponse(user.getId(), user.getEmail(), user.getDisplayName());
  }
}
