package io.github.dariogguillen.chess.exception;

/**
 * Thrown when a caller tries to join a room that already holds the maximum number of players (two).
 * Mapped to HTTP 409 by {@link GlobalExceptionHandler} with error code {@code ROOM_FULL}.
 *
 * <p>This is a genuine race condition under concurrency: two clients can both observe a 1-player
 * room and both attempt to be the second player. {@code RoomService} uses {@code
 * ConcurrentHashMap.compute} to serialize the join sequence for a given room id, so exactly one of
 * the two completes successfully and the other observes the room as full and receives this
 * exception.
 */
public class RoomFullException extends ConflictException {

  /**
   * Builds the exception with a message naming the room that was already full.
   *
   * @param roomId the room that could not be joined; non-null.
   */
  public RoomFullException(String roomId) {
    super("Room " + roomId + " already has 2 players.");
  }
}
