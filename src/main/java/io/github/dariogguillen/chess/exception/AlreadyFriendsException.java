package io.github.dariogguillen.chess.exception;

/**
 * Thrown when a friend-request is sent to a user with whom the caller already has an ACCEPTED
 * friendship (feature 23.8, friends-list). Mapped to HTTP 409 with code {@code ALREADY_FRIENDS} by
 * {@link GlobalExceptionHandler}.
 *
 * <p>Distinct from {@link DuplicateFriendRequestException}, which covers an outstanding PENDING
 * request. Both are conflicts (the request cannot be applied given the current relationship state),
 * but they are different user-facing situations and carry different codes.
 */
public class AlreadyFriendsException extends ConflictException {

  /** Builds the exception. The message names no ids — the conflict is self-explanatory. */
  public AlreadyFriendsException() {
    super("You are already friends with this user.");
  }
}
