package io.github.dariogguillen.chess.web.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code PUT /api/me/password} (feature 23.91). The caller proves possession of
 * the current password and supplies the replacement; the service verifies {@code currentPassword}
 * via {@code PasswordEncoder.matches} against the stored hash before applying the new one.
 *
 * <p>Bean Validation runs ahead of {@code ProfileService}: a violation surfaces as {@code
 * MethodArgumentNotValidException} → 400 {@code VALIDATION_FAILED}. Only {@code newPassword}
 * carries the strength bounds — {@code @Size(min = 8, max = 72)} mirrors {@code
 * RegisterRequest.password} (72 is BCrypt's input cap; longer input would be silently truncated by
 * the hash). {@code currentPassword} is only required to be present ({@code @NotBlank}); its
 * correctness is a runtime check, not a syntactic one, so a wrong-but-well-formed value is a 401,
 * not a 400.
 *
 * @param currentPassword the user's existing plain-text password; never logged, never returned.
 * @param newPassword the replacement plain-text password (8-72 chars); never logged, never
 *     returned. BCrypt-hashed by {@code ProfileService} before persistence.
 */
public record ChangePasswordRequest(
    @Schema(description = "The user's existing password. Never logged or returned.") @NotBlank
        String currentPassword,
    @Schema(
            description =
                "The new password; 8-72 chars (72 is BCrypt's input cap). Never logged or"
                    + " returned.")
        @NotBlank
        @Size(min = 8, max = 72)
        String newPassword) {}
