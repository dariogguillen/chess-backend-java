package io.github.dariogguillen.chess.exception;

import java.util.UUID;

/**
 * Thrown when a friend-request operation (accept, reject, or cancel) targets a request id that does
 * not exist <em>or</em> that exists but the caller is not a participant of (feature 23.8,
 * friends-list). Mapped to HTTP 404 with code {@code FRIEND_REQUEST_NOT_FOUND} by {@link
 * GlobalExceptionHandler}.
 *
 * <p><strong>No existence leak.</strong> A caller who is not a participant of the request gets the
 * exact same 404 / {@code FRIEND_REQUEST_NOT_FOUND} as a caller naming a request that was never
 * created. We deliberately do NOT return 403 in the not-a-participant case, because a 403 would
 * confirm the request exists — letting an attacker probe for valid request ids. Same code for "does
 * not exist" and "not yours" is the privacy-preserving choice.
 */
public class FriendRequestNotFoundException extends NotFoundException {

  /**
   * Builds the exception for a missing or inaccessible friend request.
   *
   * @param requestId the request id that could not be acted on by the caller.
   */
  public FriendRequestNotFoundException(UUID requestId) {
    super("No pending friend request " + requestId + " is accessible to the caller.");
  }
}
