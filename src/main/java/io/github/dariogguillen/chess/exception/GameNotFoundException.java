package io.github.dariogguillen.chess.exception;

/**
 * Thrown when a caller references a game id that the {@code GameStore} has no entry for. Mapped to
 * HTTP 404 by {@link GlobalExceptionHandler} with error code {@code GAME_NOT_FOUND}.
 *
 * <p>Typical sites: {@code GET /api/games/{id}} and {@code POST /api/games/{id}/moves} with an
 * unknown {@code id}.
 */
public class GameNotFoundException extends NotFoundException {

  /**
   * Builds the exception, embedding the offending game id in the message so the response body and
   * the logs both name it.
   *
   * @param gameId the game id that could not be located; non-null.
   */
  public GameNotFoundException(String gameId) {
    super("Game " + gameId + " does not exist.");
  }
}
