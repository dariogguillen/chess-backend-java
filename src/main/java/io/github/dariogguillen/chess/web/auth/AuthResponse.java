package io.github.dariogguillen.chess.web.auth;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response body for both {@code POST /api/auth/register} (201) and {@code POST /api/auth/login}
 * (200). Reuses {@link MeResponse} for the user payload so the frontend's "current user" view is a
 * single type across {@code /api/me}, {@code /api/auth/register}, and {@code /api/auth/login}.
 *
 * @param token the freshly issued JWT; the client should send it back as {@code Authorization:
 *     Bearer &lt;token&gt;} on subsequent authenticated requests.
 * @param user the authenticated user's profile payload.
 */
public record AuthResponse(
    @Schema(
            description =
                "JWT to send on subsequent authenticated requests as Authorization: Bearer ...")
        String token,
    MeResponse user) {}
