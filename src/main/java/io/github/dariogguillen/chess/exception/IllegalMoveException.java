package io.github.dariogguillen.chess.exception;

import io.github.dariogguillen.chess.domain.Move;

/**
 * Thrown when chesslib rejects a candidate move in a game. Mapped to HTTP 422 by {@link
 * GlobalExceptionHandler} with error code {@code ILLEGAL_MOVE}.
 *
 * <p>"Illegal" here means structurally well-formed (the {@link Move} compact constructor accepted
 * the {@code from}/{@code to}/{@code promotion} triple) but not reachable in the current position —
 * a pawn jumping three squares, a piece moving through an obstacle, a move that would leave the
 * mover's king in check, and so on. {@link NotYourTurnException} covers the related but distinct
 * "wrong caller" rejection.
 */
public class IllegalMoveException extends UnprocessableException {

  /**
   * Builds the exception with a message naming the offending move and the game it was attempted in.
   *
   * @param gameId the game in which the move was attempted; non-null.
   * @param move the move chesslib rejected; non-null.
   */
  public IllegalMoveException(String gameId, Move move) {
    super(
        "Move "
            + move.from().value()
            + "-"
            + move.to().value()
            + " is illegal in game "
            + gameId
            + ".");
  }
}
