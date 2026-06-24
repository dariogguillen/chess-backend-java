package io.github.dariogguillen.chess.exception;

import java.util.UUID;

/**
 * Thrown when {@code DELETE /api/me/friends/{userId}} names a user with whom the caller has no
 * ACCEPTED friendship (feature 23.8, friends-list). Mapped to HTTP 404 with code {@code
 * FRIEND_NOT_FOUND} by {@link GlobalExceptionHandler}.
 *
 * <p>Distinct from {@link FriendRequestNotFoundException} ("no such pending request"): this is "you
 * two are not friends", which is a separate user-facing condition with its own code.
 */
public class FriendNotFoundException extends NotFoundException {

  /**
   * Builds the exception for a non-existent accepted friendship.
   *
   * @param userId the id of the user the caller is not friends with.
   */
  public FriendNotFoundException(UUID userId) {
    super("No accepted friendship exists with user " + userId + ".");
  }
}
