package io.github.dariogguillen.chess.exception;

/**
 * Thrown by {@code RoomService.joinRoom} when the caller supplies no join token, or the wrong one,
 * for a token-protected room (feature 22.7, `room-access-tokens`). The {@code joinToken} is the
 * secret capability the creator receives at room-creation time and shares only with the intended
 * opponent; possession of the {@code roomId} alone (which anyone watching the room holds) does not
 * authorise taking the second-player slot.
 *
 * <p>Mapped to HTTP 403 with code {@code INVALID_JOIN_TOKEN} by {@link GlobalExceptionHandler}.
 * Like {@link InvalidCredentialsException}, it extends {@link ChessException} directly — there is
 * no {@code ForbiddenException} umbrella in the hierarchy yet, and a single 403 case does not
 * justify one; if a future feature adds a second 403, the umbrella can be promoted then without
 * churning this class.
 *
 * <p>The validation deliberately does not distinguish "no token supplied" from "wrong token
 * supplied" — both are a failure of the same capability check and surface the same code, so the
 * response leaks nothing about whether a guess was close.
 */
public class InvalidJoinTokenException extends ChessException {

  /**
   * Builds a new {@code InvalidJoinTokenException} for the given room.
   *
   * @param roomId the id of the room whose join token check failed.
   */
  public InvalidJoinTokenException(String roomId) {
    super("Invalid or missing join token for room " + roomId);
  }
}
