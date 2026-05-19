package io.github.dariogguillen.chess.exception;

/**
 * Marker subtype for "the request is well-formed and references existing resources, but a domain
 * rule rejects it". Mapped to HTTP 422 by {@link GlobalExceptionHandler}. Concrete subclasses (e.g.
 * {@link IllegalMoveException}, {@link NotYourTurnException}) carry the rule-specific error code.
 *
 * <p>This intermediate layer exists so that the handler can match on a single supertype and so that
 * "any 422 from us" can be caught in tests without enumerating every subclass.
 */
public abstract class UnprocessableException extends ChessException {

  /**
   * Builds a new unprocessable-entity exception with the given message.
   *
   * @param message the human-readable explanation of the rejected rule.
   */
  protected UnprocessableException(String message) {
    super(message);
  }
}
