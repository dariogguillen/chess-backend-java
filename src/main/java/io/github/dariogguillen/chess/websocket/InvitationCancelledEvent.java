package io.github.dariogguillen.chess.websocket;

/**
 * {@link InvitationEvent} pushed to the <em>invitee</em>'s private queue ({@code
 * /user/queue/invitations}) when the inviter cancels an invitation they had sent. Lets the
 * invitee's banner clear in real time.
 *
 * @param type the discriminator constant {@code "INVITATION_CANCELLED"}; set by the convenience
 *     constructor.
 * @param roomId the room whose invitation was cancelled.
 */
public record InvitationCancelledEvent(String type, String roomId) implements InvitationEvent {

  /** Stable discriminator value emitted in JSON as {@code "type":"INVITATION_CANCELLED"}. */
  public static final String TYPE = "INVITATION_CANCELLED";

  /**
   * Convenience constructor — the only call site producing this event. Pins {@link #type} to {@link
   * #TYPE}.
   *
   * @param roomId the room whose invitation was cancelled.
   */
  public InvitationCancelledEvent(String roomId) {
    this(TYPE, roomId);
  }
}
