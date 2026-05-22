package io.github.dariogguillen.chess.web.room;

import io.github.dariogguillen.chess.domain.RoomStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/**
 * Response body for {@code GET /api/rooms/{id}}. Carries enough state for a client that polled (or
 * that arrived late to {@code /topic/rooms/{roomId}} and needs to reconcile) to decide what to do
 * next: stay on the lobby ({@code WAITING_FOR_PLAYER}, {@code gameId == null}), or transition to
 * the game flow ({@code ACTIVE}, {@code gameId != null}).
 *
 * <p>The {@link RoomStatus} enum is exposed natively (its source literals) rather than mapped to a
 * presentation-specific vocabulary: the frontend already understands the three states and a mapping
 * layer would obscure rather than aid.
 *
 * @param roomId the room id (6-char short code).
 * @param players the players currently in the room; 1 entry while {@link
 *     RoomStatus#WAITING_FOR_PLAYER}, 2 entries while {@link RoomStatus#ACTIVE}.
 * @param gameId the id of the chess game associated with the room; {@code null} while the room is
 *     {@link RoomStatus#WAITING_FOR_PLAYER}, non-{@code null} once the second player joined.
 * @param status the lifecycle status of the room, exposed as the {@link RoomStatus} enum literal.
 */
@Schema(
    name = "RoomDetailsResponse",
    description = "Current state of a room: members, derived roles, game association, status.")
public record RoomDetailsResponse(
    @Schema(
            description =
                "6-char short code from the alphabet ABCDEFGHJKMNPQRSTUVWXYZ23456789. "
                    + "Case-insensitive in URLs; canonical uppercase form returned here.",
            example = "K7M3X9")
        String roomId,
    @Schema(
            description =
                "Players in the room. Index 0 is the creator (WHITE); index 1 (when present) is "
                    + "the joiner (BLACK). The array has 1 element while WAITING_FOR_PLAYER and 2 "
                    + "while ACTIVE.")
        List<PlayerInRoom> players,
    @Schema(
            description =
                "UUID of the chess game associated with the room. Null while the room is "
                    + "WAITING_FOR_PLAYER (no game has been created yet); non-null once ACTIVE.",
            example = "0d52a8a0-aaaa-bbbb-cccc-ddddeeee0000",
            nullable = true)
        UUID gameId,
    @Schema(
            description =
                "Lifecycle status. WAITING_FOR_PLAYER: one player, no game. ACTIVE: two players, "
                    + "game in progress. CLOSED: room no longer accepts activity.",
            example = "ACTIVE")
        RoomStatus status) {}
