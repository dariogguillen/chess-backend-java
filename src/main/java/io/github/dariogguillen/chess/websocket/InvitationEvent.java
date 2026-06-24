package io.github.dariogguillen.chess.websocket;

/**
 * Discriminated union for the per-user STOMP events delivered on {@code /user/queue/invitations}
 * (feature 23.9, {@code direct-invitations}). Like {@link RoomEvent}, each variant carries an
 * explicit {@code type} field — a plain JSON property — so subscribers branch on the variant
 * without polymorphic Jackson machinery ({@code @JsonTypeInfo}).
 *
 * <p>Unlike {@link RoomEvent}, these events are <em>not</em> broadcast to a shared topic; they are
 * routed to a single user's session(s) via {@code SimpMessagingTemplate.convertAndSendToUser(...)},
 * which resolves the principal name (feature 20 sets {@code StompPrincipal.getName() = userId}) to
 * the {@code /user/{userId}/queue/invitations} private destination. The three variants flow in two
 * directions:
 *
 * <ul>
 *   <li>{@link InvitationReceivedEvent} → the invitee, on send.
 *   <li>{@link InvitationDeclinedEvent} → the inviter, when the invitee declines.
 *   <li>{@link InvitationCancelledEvent} → the invitee, when the inviter cancels.
 * </ul>
 *
 * <p>Each variant carries the room id; the accept path deliberately has <em>no</em> variant here —
 * the inviter learns of an accept through the existing {@link RoomJoinedEvent} on {@code
 * /topic/rooms/{roomId}} (feature 9.5), so no redundant signal is emitted.
 */
public sealed interface InvitationEvent
    permits InvitationReceivedEvent, InvitationDeclinedEvent, InvitationCancelledEvent {

  /**
   * The discriminator string. Each variant returns its own stable, upper-snake-case constant (e.g.
   * {@code "INVITATION_RECEIVED"}).
   */
  String type();

  /**
   * The room id every variant carries. Identifies which invitation the event is about on the
   * receiving client.
   */
  String roomId();
}
