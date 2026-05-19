package io.github.dariogguillen.chess.web.room;

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
 * @param roomId the room id (six-character short code on create, the same id passed in on join).
 * @param playerId the server-assigned UUID for the caller.
 * @param role {@code "WHITE"} for the creator, {@code "BLACK"} for the joiner.
 * @param gameId the freshly created game id on join; {@code null} on create.
 */
public record RoomResponse(String roomId, String playerId, String role, String gameId) {}
