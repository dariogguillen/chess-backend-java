package io.github.dariogguillen.chess.web.room;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * Unified response body for both {@code POST /api/rooms} (create) and {@code POST
 * /api/rooms/{id}/join} (join). The shape is identical so that the client only has to know one
 * record; {@code gameId} is the differentiator:
 *
 * <ul>
 *   <li>On <strong>create</strong>: {@code gameId} is {@code null} because the room has only one
 *       player and no game has been started yet.
 *   <li>On <strong>join</strong>: {@code gameId} is the id of the freshly created game.
 * </ul>
 *
 * <p>Nulls are emitted in the serialized JSON (Jackson's default) so the field is always present —
 * easier for the client than checking key existence.
 *
 * <p>{@code playerId} and {@code gameId} are {@link UUID}s; Jackson serialises them to plain JSON
 * strings, so the wire shape is identical to the previous {@code String}-typed version.
 *
 * <p>{@code joinToken} (feature 22.7, `room-access-tokens`) is the secret capability the join
 * endpoint requires. It is non-{@code null} <strong>only on the create response</strong> — the
 * creator is the sole party that obtains it, and shares it out-of-band only with the intended
 * opponent. On the join response it is always {@code null} (the joiner is already in and never
 * needs it), and it is never exposed by {@code GET /api/rooms/{id}} (that path uses {@code
 * RoomDetailsResponse}, which has no token field).
 *
 * @param roomId the room id (six-character short code on create, the same id passed in on join).
 * @param playerId the server-assigned UUID for the caller.
 * @param role the caller's resolved side: on create the side the creator chose ({@code "WHITE"} by
 *     default, {@code "BLACK"} if requested, or a coin-flip result for {@code RANDOM}); on join the
 *     opposite of the creator's side.
 * @param gameId the freshly created game id on join; {@code null} on create.
 * @param joinToken the secret required to join the room; non-{@code null} only on the create
 *     response, {@code null} on the join response.
 */
public record RoomResponse(
    @Schema(
            description =
                "6-char short code from the alphabet ABCDEFGHJKMNPQRSTUVWXYZ23456789. "
                    + "Case-insensitive in URLs; canonical uppercase form returned here.",
            example = "K7M3X9")
        String roomId,
    @Schema(
            description = "Server-generated UUID identifying the caller as a player in the room.",
            example = "8b3c1f04-1234-5678-9abc-def012345678")
        UUID playerId,
    @Schema(
            description =
                "Side assigned to the caller. On create it is the creator's chosen side (WHITE by "
                    + "default, or BLACK / a RANDOM coin-flip result if requested); on join it is "
                    + "the opposite of the creator's side.",
            example = "WHITE")
        String role,
    @Schema(
            description =
                "UUID of the chess game associated with the room. Null on the create response "
                    + "(no game exists yet); non-null on the join response.",
            example = "0d52a8a0-aaaa-bbbb-cccc-ddddeeee0000",
            nullable = true)
        UUID gameId,
    @Schema(
            description =
                "Secret join token required by POST /api/rooms/{id}/join. Non-null ONLY on the "
                    + "create response — the creator keeps it and shares it out-of-band with the "
                    + "opponent. Null on the join response, and never returned by GET "
                    + "/api/rooms/{id}. Possession of the roomId alone (used for watching) does "
                    + "not authorise joining.",
            example = "8b3c1f04-1234-5678-9abc-def012345678",
            nullable = true)
        String joinToken) {}
