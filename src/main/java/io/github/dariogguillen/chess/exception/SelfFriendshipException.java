package io.github.dariogguillen.chess.exception;

/**
 * Thrown when a user sends a friend request using their own friend code (feature 23.8,
 * friends-list). Mapped to HTTP 422 with code {@code SELF_FRIENDSHIP} by {@link
 * GlobalExceptionHandler}.
 *
 * <p>Sits in the unprocessable (422) family rather than not-found (404) or conflict (409): the
 * request is well-formed and references a real, existing user (the caller themselves), but a domain
 * rule forbids befriending oneself.
 */
public class SelfFriendshipException extends UnprocessableException {

  /** Builds the exception. The message names no ids — the rule is self-explanatory. */
  public SelfFriendshipException() {
    super("You cannot send a friend request to yourself.");
  }
}
