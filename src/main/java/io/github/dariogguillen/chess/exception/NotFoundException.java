package io.github.dariogguillen.chess.exception;

/**
 * Marker subtype for "the resource you asked for does not exist". Mapped to HTTP 404 by {@link
 * GlobalExceptionHandler}. Concrete subclasses (e.g. {@link RoomNotFoundException}) carry the
 * resource-specific error code surfaced in the response body.
 *
 * <p>This intermediate layer exists so that the handler can match on a single supertype and so that
 * "any 404 from us" can be caught in tests without enumerating every subclass.
 */
public abstract class NotFoundException extends ChessException {

  /**
   * Builds a new not-found exception with the given message.
   *
   * @param message the human-readable explanation; typically names the missing resource.
   */
  protected NotFoundException(String message) {
    super(message);
  }
}
