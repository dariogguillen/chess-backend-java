package io.github.dariogguillen.chess.exception;

import java.util.UUID;

/**
 * Thrown when a caller attempts a move in a game where it is the other side's turn. Mapped to HTTP
 * 422 by {@link GlobalExceptionHandler} with error code {@code NOT_YOUR_TURN}.
 *
 * <p>Sits alongside {@link IllegalMoveException} as a 422: the request is syntactically and
 * authorisatively valid, but the domain rule (turn order) rejects it. The handler does not surface
 * the expected player id in the response body to avoid leaking it; the message — used for
 * server-side logs and diagnostics — does name it for traceability.
 */
public class NotYourTurnException extends UnprocessableException {

  /**
   * Builds the exception with a message naming the game and the expected player id.
   *
   * @param gameId the game in which the move was attempted; non-null.
   * @param expectedPlayerId the id of the player whose turn it actually is; non-null.
   */
  public NotYourTurnException(UUID gameId, UUID expectedPlayerId) {
    super("It is not your turn in game " + gameId + "; expected player " + expectedPlayerId + ".");
  }
}
