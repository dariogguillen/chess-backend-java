package io.github.dariogguillen.chess.exception;

/**
 * Thrown when a friend-request is sent with a {@code friendCode} that no user owns (feature 23.8,
 * friends-list). Mapped to HTTP 404 with code {@code FRIEND_CODE_NOT_FOUND} by {@link
 * GlobalExceptionHandler}.
 *
 * <p>The message deliberately does not echo the supplied code back verbatim into anything sensitive
 * — the code is the only discovery surface, and a 404 already tells the caller "no such user",
 * which is the intended, non-enumerable signal.
 */
public class FriendCodeNotFoundException extends NotFoundException {

  /**
   * Builds the exception for an unknown friend code.
   *
   * @param friendCode the code that matched no user.
   */
  public FriendCodeNotFoundException(String friendCode) {
    super("No user owns friend code " + friendCode + ".");
  }
}
