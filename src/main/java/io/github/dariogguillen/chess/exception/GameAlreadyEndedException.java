package io.github.dariogguillen.chess.exception;

import io.github.dariogguillen.chess.domain.GameStatus;
import java.util.UUID;

/**
 * Thrown when a caller attempts a move in a game whose status is already terminal (checkmate,
 * stalemate, draw, abandoned). Mapped to HTTP 409 by {@link GlobalExceptionHandler} with error code
 * {@code GAME_ALREADY_ENDED}.
 *
 * <p>Sits in the conflict family because the request is well-formed and references an existing
 * game, but the game's state forbids the transition.
 */
public class GameAlreadyEndedException extends ConflictException {

  /**
   * Builds the exception with a message naming the game and its terminal status.
   *
   * @param gameId the game in which the move was attempted; non-null.
   * @param status the terminal status the game is in; non-null.
   */
  public GameAlreadyEndedException(UUID gameId, GameStatus status) {
    super("Game " + gameId + " has already ended with status " + status + ".");
  }
}
