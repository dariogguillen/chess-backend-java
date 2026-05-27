package io.github.dariogguillen.chess.web.auth;

import io.github.dariogguillen.chess.exception.ErrorResponse;
import io.github.dariogguillen.chess.service.auth.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for the two user-visible auth flows landed by feature 17: register and login. Both
 * return a freshly minted JWT plus the canonical {@link MeResponse} so the frontend can move from
 * "submitted credentials" to "authenticated user known" in a single round-trip. The {@code Tag}
 * matches {@link MeController}'s — Swagger groups all three endpoints together under
 * "Authentication".
 *
 * <p>The controller stays routing-only: it delegates to {@link AuthService} and lets {@code
 * GlobalExceptionHandler} translate the typed exceptions ({@code EmailAlreadyTakenException},
 * {@code InvalidCredentialsException}, plus the framework-side {@code
 * MethodArgumentNotValidException}) into structured {@link ErrorResponse} bodies.
 */
@Tag(name = "Authentication", description = "Authenticated-user information.")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

  private final AuthService authService;

  public AuthController(AuthService authService) {
    this.authService = authService;
  }

  @Operation(
      summary = "Register a new account",
      description =
          "Creates a user with the given email + password (BCrypt-hashed) + display name and"
              + " returns a freshly issued JWT. The token is immediately usable as the"
              + " Authorization: Bearer header on subsequent authenticated requests — there is no"
              + " separate confirmation step.")
  @ApiResponse(
      responseCode = "201",
      description = "Account created; response carries the JWT and the user profile.",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = AuthResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description =
          "Validation failed (malformed email, missing field, password outside 8-72 chars, etc.).",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "409",
      description = "An account with this email already exists.",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @PostMapping(path = "/register", consumes = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
    return authService.register(request);
  }

  @Operation(
      summary = "Log in with email and password",
      description =
          "Validates the credentials and returns a freshly issued JWT. The failure response is"
              + " uniform — wrong password and unknown email both surface as 401"
              + " INVALID_CREDENTIALS with the same generic message, to avoid leaking which"
              + " accounts exist.")
  @ApiResponse(
      responseCode = "200",
      description = "Authenticated; response carries the JWT and the user profile.",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = AuthResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description = "Validation failed (malformed email, missing field).",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Invalid email or password.",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @PostMapping(path = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
  public AuthResponse login(@Valid @RequestBody LoginRequest request) {
    return authService.authenticate(request);
  }
}
