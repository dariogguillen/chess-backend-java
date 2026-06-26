package io.github.dariogguillen.chess.web.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code PATCH /api/me} (feature 23.91). Carries the single editable identity
 * field — the display name. Email is deliberately not editable here (it is the unique login
 * identity and would need re-verification); the password has its own endpoint ({@code PUT
 * /api/me/password}).
 *
 * <p>Bean Validation runs ahead of {@code ProfileService}: a violation surfaces as {@code
 * MethodArgumentNotValidException} → 400 {@code VALIDATION_FAILED} via {@code
 * GlobalExceptionHandler} before the service is reached. The {@code @NotBlank @Size(max = 100)}
 * pair mirrors {@code RegisterRequest.displayName} so a name accepted at registration is accepted
 * on rename, and the 100 cap matches the {@code users.display_name} column.
 *
 * @param displayName the new human-readable name; non-blank, capped at 100 chars.
 */
public record UpdateProfileRequest(
    @Schema(description = "New human-readable name shown in the UI.", example = "Alice")
        @NotBlank
        @Size(max = 100)
        String displayName) {}
