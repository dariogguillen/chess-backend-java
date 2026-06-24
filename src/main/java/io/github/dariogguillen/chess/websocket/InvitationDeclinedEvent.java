package io.github.dariogguillen.chess.websocket;

import java.util.UUID;

/**
 * {@link InvitationEvent} pushed to the <em>inviter</em>'s private queue ({@code
 * /user/queue/invitations}) when the invitee declines an invitation. Lets the inviter's UI clear
 * the "invited X" state in real time instead of polling.
 *
 * @param type the discriminator constant {@code "INVITATION_DECLINED"}; set by the convenience
 *     constructor.
 * @param roomId the room whose invitation was declined.
 * @param inviteeUserId the user who declined.
 */
public record InvitationDeclinedEvent(String type, String roomId, UUID inviteeUserId)
    implements InvitationEvent {

  /** Stable discriminator value emitted in JSON as {@code "type":"INVITATION_DECLINED"}. */
  public static final String TYPE = "INVITATION_DECLINED";

  /**
   * Convenience constructor — the only call site producing this event. Pins {@link #type} to {@link
   * #TYPE}.
   *
   * @param roomId the room whose invitation was declined.
   * @param inviteeUserId the user who declined.
   */
  public InvitationDeclinedEvent(String roomId, UUID inviteeUserId) {
    this(TYPE, roomId, inviteeUserId);
  }
}
