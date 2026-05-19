package io.github.dariogguillen.chess.exception;

/**
 * Thrown when a caller references a room id that the {@code RoomStore} has no entry for. Mapped to
 * HTTP 404 by {@link GlobalExceptionHandler} with error code {@code ROOM_NOT_FOUND}.
 *
 * <p>Typical site: {@code POST /api/rooms/{id}/join} with an unknown {@code id}, either because the
 * room never existed or because it expired / was removed.
 */
public class RoomNotFoundException extends NotFoundException {

  /**
   * Builds the exception, embedding the offending room id in the message so the response body and
   * the logs both name it.
   *
   * @param roomId the room id that could not be located; non-null.
   */
  public RoomNotFoundException(String roomId) {
    super("Room not found: " + roomId);
  }
}
