package io.github.dariogguillen.chess.exception;

/**
 * Marker subtype for "the request cannot be applied to the resource in its current state". Mapped
 * to HTTP 409 by {@link GlobalExceptionHandler}. Concrete subclasses (e.g. {@link
 * RoomFullException}) carry the conflict-specific error code.
 */
public abstract class ConflictException extends ChessException {

  /**
   * Builds a new conflict exception with the given message.
   *
   * @param message the human-readable explanation of the conflict.
   */
  protected ConflictException(String message) {
    super(message);
  }
}
