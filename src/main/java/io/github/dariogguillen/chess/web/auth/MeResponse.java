package io.github.dariogguillen.chess.web.auth;

import io.github.dariogguillen.chess.domain.User;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * Response body for {@code GET /api/me} (and reused as {@code AuthResponse.user} on
 * register/login). The authenticated user's public identity, minus anything sensitive — no password
 * hash, no Google sub. The {@code createdAt} timestamp is exposed (feature 23.91, "member since")
 * so the frontend's profile page can render the account age; the secret fields still never travel
 * to the client.
 *
 * <p>Construct instances via {@link #of(User)} rather than the canonical constructor. Three call
 * sites build this payload — {@code MeController}, {@code AuthService}, and {@code ProfileService}
 * — and routing them through one factory means a future field addition lands in a single place
 * instead of drifting across the three.
 *
 * @param id the authenticated user's {@link UUID}; matches the {@code sub} claim in the JWT.
 * @param email the canonical (lowercase) email associated with the account.
 * @param displayName the human-readable name shown in the UI.
 * @param createdAt the account creation timestamp ("member since"); ISO-8601 instant.
 */
public record MeResponse(
    @Schema(description = "Authenticated user's UUID; equal to the JWT 'sub' claim.") UUID id,
    @Schema(description = "Canonical lowercase email associated with the account.") String email,
    @Schema(description = "Human-readable name shown in the UI.") String displayName,
    @Schema(description = "Account creation timestamp ('member since'); ISO-8601 instant.")
        Instant createdAt) {

  /**
   * Builds the response payload from a {@link User} entity. Single construction site for the three
   * callers ({@code MeController}, {@code AuthService}, {@code ProfileService}) so any future field
   * addition is made once here.
   *
   * @param user the authenticated user; non-null.
   * @return the public-facing payload.
   */
  public static MeResponse of(User user) {
    return new MeResponse(
        user.getId(), user.getEmail(), user.getDisplayName(), user.getCreatedAt());
  }
}
