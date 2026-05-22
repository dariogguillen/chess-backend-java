package io.github.dariogguillen.chess.web.room;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * Nested element of {@link RoomDetailsResponse#players()}. Pairs a player's identity with a role
 * derived at the web boundary from join order — index 0 is {@code WHITE} (the creator), index 1
 * (when present) is {@code BLACK} (the joiner).
 *
 * <p>The role is intentionally <strong>not</strong> a field on the domain {@code Player} record:
 * keeping it boundary-only avoids leaking a presentation concern into Redis snapshots and the
 * Postgres history shape. The trade-off is documented in {@link RoomDetailsMapper}.
 *
 * @param id the server-generated player id; {@link UUID}.
 * @param displayName the human-readable label provided when the player joined.
 * @param role the side assigned to this player; {@code "WHITE"} or {@code "BLACK"}.
 */
public record PlayerInRoom(
    @Schema(
            description = "Server-generated UUID identifying the player.",
            example = "8b3c1f04-1234-5678-9abc-def012345678")
        UUID id,
    @Schema(description = "Human-readable label provided at join time.", example = "Alice")
        String displayName,
    @Schema(
            description =
                "Side assigned to the player. WHITE for the creator (players[0]), "
                    + "BLACK for the joiner (players[1]).",
            allowableValues = {"WHITE", "BLACK"},
            example = "WHITE")
        String role) {}
