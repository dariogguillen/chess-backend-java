package io.github.dariogguillen.chess.websocket;

import io.github.dariogguillen.chess.domain.TimeControl;
import java.util.UUID;

/**
 * {@link InvitationEvent} pushed to the <em>invitee</em>'s private queue ({@code
 * /user/queue/invitations}) the moment an inviter sends them an invitation. Carries everything the
 * invitee's banner needs to render "X invited you to a {timeControl} game" without a follow-up REST
 * call — but deliberately <strong>not</strong> the room's secret join token, which never leaves the
 * server (accept performs the join server-side).
 *
 * <p>The {@code timeControl} is {@code null} for an untimed room, mirroring the domain {@link
 * TimeControl} optionality.
 *
 * @param type the discriminator constant {@code "INVITATION_RECEIVED"}; set by the convenience
 *     constructor.
 * @param roomId the room the invitee is invited to join.
 * @param inviterUserId the inviter's authenticated user id.
 * @param inviterDisplayName the inviter's human-readable label.
 * @param timeControl the room's declared clock, or {@code null} for an untimed room.
 */
public record InvitationReceivedEvent(
    String type,
    String roomId,
    UUID inviterUserId,
    String inviterDisplayName,
    TimeControl timeControl)
    implements InvitationEvent {

  /** Stable discriminator value emitted in JSON as {@code "type":"INVITATION_RECEIVED"}. */
  public static final String TYPE = "INVITATION_RECEIVED";

  /**
   * Convenience constructor — the only call site producing this event. Pins {@link #type} to {@link
   * #TYPE}, keeping the discriminator visible at the source rather than computed.
   *
   * @param roomId the room the invitee is invited to join.
   * @param inviterUserId the inviter's user id.
   * @param inviterDisplayName the inviter's display name.
   * @param timeControl the room's clock, or {@code null} for untimed.
   */
  public InvitationReceivedEvent(
      String roomId, UUID inviterUserId, String inviterDisplayName, TimeControl timeControl) {
    this(TYPE, roomId, inviterUserId, inviterDisplayName, timeControl);
  }
}
