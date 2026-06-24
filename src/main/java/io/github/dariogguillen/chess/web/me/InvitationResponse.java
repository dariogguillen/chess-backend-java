package io.github.dariogguillen.chess.web.me;

import io.github.dariogguillen.chess.domain.TimeControl;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * One entry of the invitee's pending-invitations list, returned by {@code GET /api/me/invitations}
 * (feature 23.9, {@code direct-invitations}). The list is filtered to still-live, still-joinable
 * rooms, so every entry the invitee sees is one they can actually accept.
 *
 * <p>The {@code side} and {@code timeControl} are derived from the <em>live</em> {@link
 * io.github.dariogguillen.chess.domain.Room} at read time (not stored on the invitation), so they
 * are never stale: {@code side} is the side the invitee would take on accept (the opposite of the
 * creator's side), and {@code timeControl} is the room's declared clock ({@code null} when
 * untimed). The room's secret join token is deliberately absent — accept performs the join
 * server-side, so the token never reaches the client.
 *
 * @param roomId the room the invitation targets.
 * @param inviterUserId the inviter's user id.
 * @param inviterDisplayName the inviter's display name (captured at send time).
 * @param timeControl the room's declared clock, or {@code null} for an untimed room.
 * @param side the side the invitee would play on accept: {@code "WHITE"} or {@code "BLACK"}.
 * @param createdAt when the invitation was created (or last refreshed).
 */
public record InvitationResponse(
    @Schema(description = "Room the invitation targets (6-char short code).", example = "K7M3X9")
        String roomId,
    @Schema(
            description = "User id of the inviter.",
            example = "8b3c1f04-1234-5678-9abc-def012345678")
        UUID inviterUserId,
    @Schema(description = "Display name of the inviter.", example = "Alice")
        String inviterDisplayName,
    @Schema(description = "The room's declared clock; null for an untimed room.", nullable = true)
        TimeControl timeControl,
    @Schema(
            description =
                "Side the invitee would take on accept (the opposite of the creator's side).",
            example = "BLACK")
        String side,
    @Schema(description = "When the invitation was created or last refreshed (ISO-8601 UTC).")
        Instant createdAt) {}
