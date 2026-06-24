package io.github.dariogguillen.chess.exception;

/**
 * Thrown when an invitation operation names a {@code (caller, roomId)} pair that has no live
 * invitation (feature 23.9, {@code direct-invitations}). Two sites raise it: the invitee's accept /
 * decline ({@code POST|DELETE /api/me/invitations/{roomId}}) when no invitation is addressed to the
 * caller for that room, and the inviter's cancel ({@code DELETE
 * /api/me/invitations/{roomId}/to/{inviteeUserId}}) when no such invitation exists.
 *
 * <p>Mapped to HTTP 404 with code {@code INVITATION_NOT_FOUND} by {@link GlobalExceptionHandler}
 * (via the {@link NotFoundException} branch and the mechanical {@code codeOf} derivation). A
 * pruned-stale entry (the room expired or filled) is indistinguishable from a never-existing one —
 * both surface this same 404, so the response leaks nothing about why the invitation is gone.
 */
public class InvitationNotFoundException extends NotFoundException {

  /**
   * Builds the exception for a missing invitation on the given room.
   *
   * @param roomId the room id the absent invitation would have targeted.
   */
  public InvitationNotFoundException(String roomId) {
    super("No live invitation found for room " + roomId + ".");
  }
}
