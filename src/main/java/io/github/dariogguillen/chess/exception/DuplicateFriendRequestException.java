package io.github.dariogguillen.chess.exception;

/**
 * Thrown when a friend-request is sent but a PENDING request already exists between the two users
 * in EITHER direction (feature 23.8, friends-list). Mapped to HTTP 409 with code {@code
 * DUPLICATE_FRIEND_REQUEST} by {@link GlobalExceptionHandler}.
 *
 * <p>The "either direction" symmetry is what the unordered-pair UNIQUE index on {@code friendships}
 * enforces at the database level: if A already sent B a request, B's attempt to send A a request is
 * the same pair and is rejected as a duplicate rather than creating a second row. The service
 * checks for the existing row up front; the DB index is the race-condition safety net behind it.
 */
public class DuplicateFriendRequestException extends ConflictException {

  /** Builds the exception. The message names no ids — the conflict is self-explanatory. */
  public DuplicateFriendRequestException() {
    super("A friend request between you and this user already exists.");
  }
}
