package io.github.dariogguillen.chess.exception;

/**
 * Thrown when a caller tries to invite a friend to — or cancel an invitation on — a room they are
 * not a player of (feature 23.9, {@code direct-invitations}). Only a member of a room may extend or
 * rescind invitations to it; possession of a room id (which any watcher holds) does not grant that
 * authority.
 *
 * <p>Mapped to HTTP 403 with code {@code NOT_ROOM_MEMBER} by {@link GlobalExceptionHandler}. Like
 * {@link InvalidJoinTokenException}, it extends {@link ChessException} directly and is handled by a
 * narrow concrete-class branch in the advice — there is no {@code ForbiddenException} umbrella in
 * the hierarchy yet, and the two 403 cases ({@code INVALID_JOIN_TOKEN}, {@code NOT_ROOM_MEMBER})
 * still do not justify promoting one. The {@code NOT_ROOM_MEMBER} code is derived mechanically by
 * {@link GlobalExceptionHandler}'s {@code codeOf}.
 */
public class NotRoomMemberException extends ChessException {

  /**
   * Builds the exception for a caller who is not a member of the given room.
   *
   * @param roomId the room the caller is not a player of.
   */
  public NotRoomMemberException(String roomId) {
    super("Caller is not a member of room " + roomId + ".");
  }
}
