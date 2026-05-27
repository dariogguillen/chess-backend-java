package io.github.dariogguillen.chess.web.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/auth/register}.
 *
 * <p>Bean Validation runs ahead of {@code AuthService}: a violation surfaces as {@code
 * MethodArgumentNotValidException} → 400 {@code VALIDATION_FAILED} via {@code
 * GlobalExceptionHandler}, so the service can assume the inputs are syntactically clean.
 *
 * <p>The {@code @Size(max = 72)} on {@code password} is not arbitrary: BCrypt silently truncates
 * its input above 72 bytes (the algorithm's block-size limitation). Capping at the same boundary
 * means two passwords that differ only beyond byte 72 cannot hash to the same value through this
 * codepath — the user is told the password is too long instead of being given a silently weakened
 * hash. The lower bound (8) is the conservative policy floor.
 *
 * @param email RFC-5321-shaped email; capped at 254 chars to match the {@code users.email} column.
 *     Trimmed and lower-cased by {@code AuthService.register} before persistence.
 * @param password the plain-text password; never logged, never returned. BCrypt-hashed by {@code
 *     AuthService.register} before persistence.
 * @param displayName the human-readable name the user picks; capped at 100 chars to match {@code
 *     users.display_name} and {@code games.{white,black}_display_name}.
 */
public record RegisterRequest(
    @Schema(description = "Account email; case-insensitive, normalised to lowercase server-side.")
        @NotBlank
        @Email
        @Size(max = 254)
        String email,
    @Schema(
            description =
                "Plain-text password; 8-72 chars (72 is BCrypt's input cap — longer passwords"
                    + " would be silently truncated by the hash function).")
        @NotBlank
        @Size(min = 8, max = 72)
        String password,
    @Schema(description = "Human-readable name shown in the UI.") @NotBlank @Size(max = 100)
        String displayName) {}
