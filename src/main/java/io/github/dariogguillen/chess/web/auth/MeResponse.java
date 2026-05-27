package io.github.dariogguillen.chess.web.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * Response body for {@code GET /api/me}. The authenticated user's identity, minus anything
 * sensitive — no password hash, no Google sub, no creation timestamp. Those fields are useful
 * server-side but never travel to the client.
 *
 * @param id the authenticated user's {@link UUID}; matches the {@code sub} claim in the JWT.
 * @param email the canonical (lowercase) email associated with the account.
 * @param displayName the human-readable name shown in the UI.
 */
public record MeResponse(
    @Schema(description = "Authenticated user's UUID; equal to the JWT 'sub' claim.") UUID id,
    @Schema(description = "Canonical lowercase email associated with the account.") String email,
    @Schema(description = "Human-readable name shown in the UI.") String displayName) {}
