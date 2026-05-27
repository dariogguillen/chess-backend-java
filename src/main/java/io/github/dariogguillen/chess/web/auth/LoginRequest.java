package io.github.dariogguillen.chess.web.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /api/auth/login}.
 *
 * <p>Validation deliberately differs from {@link RegisterRequest}: there is no {@code @Size} on
 * {@code password}. A wrong-length password is a credentials error (401 {@code
 * INVALID_CREDENTIALS}), not a validation error (400 {@code VALIDATION_FAILED}). Surfacing a 400
 * for "too short" or "too long" would tell an attacker that the password-length policy is X..Y,
 * which is one bit of information they should not get from a failed login attempt.
 *
 * @param email RFC-5321-shaped email. Trimmed and lower-cased by {@code AuthService.authenticate}
 *     before lookup so the user can sign in regardless of capitalisation.
 * @param password the plain-text password; never logged. Compared against the stored BCrypt hash by
 *     {@code AuthService.authenticate}.
 */
public record LoginRequest(
    @Schema(description = "Account email; case-insensitive, normalised to lowercase server-side.")
        @NotBlank
        @Email
        String email,
    @Schema(description = "Plain-text password.") @NotBlank String password) {}
