package io.github.dariogguillen.chess.service;

import io.github.dariogguillen.chess.cache.InvitationStore;
import io.github.dariogguillen.chess.domain.Invitation;
import io.github.dariogguillen.chess.domain.Player;
import io.github.dariogguillen.chess.domain.Room;
import io.github.dariogguillen.chess.domain.RoomStatus;
import io.github.dariogguillen.chess.domain.Side;
import io.github.dariogguillen.chess.domain.TimeControl;
import io.github.dariogguillen.chess.exception.FriendNotFoundException;
import io.github.dariogguillen.chess.exception.InvitationNotFoundException;
import io.github.dariogguillen.chess.exception.NotRoomMemberException;
import io.github.dariogguillen.chess.exception.RoomFullException;
import io.github.dariogguillen.chess.exception.RoomNotFoundException;
import io.github.dariogguillen.chess.persistence.FriendshipRepository;
import io.github.dariogguillen.chess.service.RoomService.JoinedRoom;
import io.github.dariogguillen.chess.websocket.InvitationCancelledEvent;
import io.github.dariogguillen.chess.websocket.InvitationDeclinedEvent;
import io.github.dariogguillen.chess.websocket.InvitationReceivedEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the direct-invitation lifecycle (feature 23.9, {@code direct-invitations}): an authenticated
 * user invites one of their ACCEPTED friends to a FRIEND room they created, the invitee accepts /
 * declines, and the inviter can cancel. The invitation is the second half of the social pair, built
 * on the accepted-friends set (feature 23.8) and the room join token (feature 22.7).
 *
 * <p><strong>Invite-to-an-existing-room model.</strong> The inviter creates a FRIEND room first
 * (obtaining the join token from {@code POST /api/rooms}) and then invites a friend to
 * <em>that</em> room — this is not a "challenge" that creates a room on accept. The invitation
 * stores the room id, the inviter's id and display name, and the creation instant; it never stores
 * the join token.
 *
 * <p><strong>Accept = server-side join.</strong> {@link #accept(UUID, String, String)} reads the
 * join token from the live {@link Room} on the server and joins via {@link RoomService#joinRoom},
 * so the token never travels to the invitee's client. Because the accept reuses {@code joinRoom},
 * the inviter is notified by the <em>existing</em> {@code RoomJoinedEvent} on {@code
 * /topic/rooms/{roomId}} (feature 9.5) — this service emits no new event for accept.
 *
 * <p><strong>Ephemeral, re-validated liveness.</strong> Invitations live in Redis tied to the room
 * TTL ({@link InvitationStore}); there is no durable table. The store knows nothing about room
 * liveness, so every read/accept path re-validates against the live {@link RoomStore} (room exists,
 * {@link RoomStatus#WAITING_FOR_PLAYER}, has a free slot, has a join token) and lazily prunes stale
 * entries. A stale or missing invitation is indistinguishable to the caller — both surface {@link
 * InvitationNotFoundException} (404).
 *
 * <p><strong>Transactions.</strong> Only the friend-gate check touches JPA, so
 * {@code @Transactional(readOnly = true)} is applied narrowly to {@link #send}; the Redis store ops
 * and the STOMP pushes are not JPA-transactional and the other methods (which do not touch JPA)
 * carry no transaction annotation.
 *
 * <p><strong>Push hygiene.</strong> The three private-queue pushes ({@link
 * InvitationReceivedEvent}, {@link InvitationDeclinedEvent}, {@link InvitationCancelledEvent})
 * mirror {@code RoomService.broadcastRoomJoinedEvent}: best-effort, wrapped in try/catch, INFO on
 * success and WARN on failure (feature 11.8). A push failure never fails the REST call — the REST
 * list is the fallback for a missed push.
 */
@Service
public class InvitationService {

  private static final Logger log = LoggerFactory.getLogger(InvitationService.class);

  /** Per-user private destination the broker routes via the {@code /user} prefix. */
  private static final String INVITATIONS_DESTINATION = "/queue/invitations";

  private final FriendshipRepository friendships;
  private final InvitationStore invitationStore;
  private final RoomStore roomStore;
  private final RoomService roomService;
  private final SimpMessagingTemplate messagingTemplate;
  private final Clock clock;

  public InvitationService(
      FriendshipRepository friendships,
      InvitationStore invitationStore,
      RoomStore roomStore,
      RoomService roomService,
      SimpMessagingTemplate messagingTemplate,
      Clock clock) {
    this.friendships = friendships;
    this.invitationStore = invitationStore;
    this.roomStore = roomStore;
    this.roomService = roomService;
    this.messagingTemplate = messagingTemplate;
    this.clock = clock;
  }

  /**
   * Sends (or idempotently re-sends) an invitation from {@code inviterUserId} to {@code
   * friendUserId} for the room {@code roomId}. Validates in order: the two must be ACCEPTED
   * friends, the room must exist, the caller must be a player of it, and the room must have a free
   * joinable slot. On success the invitation is stored and an {@link InvitationReceivedEvent} is
   * pushed to the invitee's private queue.
   *
   * <p>Sending the same {@code (room, invitee)} pair again overwrites the stored value and
   * refreshes its TTL — idempotent, no error.
   *
   * @param inviterUserId the authenticated caller's user id.
   * @param inviterDisplayName the caller's display name, captured onto the stored invitation.
   * @param roomId the room the caller created and is inviting into.
   * @param friendUserId the invitee — must be an ACCEPTED friend of the caller.
   * @throws FriendNotFoundException if the two are not ACCEPTED friends (404). Also covers a
   *     self-invite, since one cannot befriend oneself.
   * @throws RoomNotFoundException if the room does not exist (404).
   * @throws NotRoomMemberException if the caller is not a player of the room (403).
   * @throws RoomFullException if the room has no free joinable slot — already ACTIVE/full, or a BOT
   *     room with no join token (409).
   */
  @Transactional(readOnly = true)
  public void send(
      UUID inviterUserId, String inviterDisplayName, String roomId, UUID friendUserId) {
    if (!friendships.existsAcceptedBetween(inviterUserId, friendUserId)) {
      throw new FriendNotFoundException(friendUserId);
    }
    Room room = roomStore.findById(roomId).orElseThrow(() -> new RoomNotFoundException(roomId));
    if (!isMember(room, inviterUserId)) {
      throw new NotRoomMemberException(roomId);
    }
    if (!isJoinable(room)) {
      throw new RoomFullException(roomId);
    }
    Invitation invitation =
        new Invitation(roomId, inviterUserId, inviterDisplayName, Instant.now(clock));
    invitationStore.save(friendUserId, invitation);
    log.info(
        "Invitation sent: roomId={}, inviterUserId={}, inviteeUserId={}",
        roomId,
        inviterUserId,
        friendUserId);
    pushToUser(
        friendUserId,
        new InvitationReceivedEvent(roomId, inviterUserId, inviterDisplayName, room.timeControl()),
        "InvitationReceivedEvent");
  }

  /**
   * Returns the caller's pending incoming invitations, filtered to still-live, still-joinable
   * rooms. Backed by the per-invitee index (no keyspace scan); each entry is re-validated against
   * the live room and stale entries (room expired / now full / no token) are pruned lazily here.
   *
   * @param inviteeUserId the caller's user id.
   * @return the live invitations, each projected to a {@link LiveInvitation} carrying the derived
   *     side and time control from the live room.
   */
  public List<LiveInvitation> listIncoming(UUID inviteeUserId) {
    List<Invitation> stored = invitationStore.findByInvitee(inviteeUserId);
    List<LiveInvitation> live = new ArrayList<>(stored.size());
    for (Invitation invitation : stored) {
      Optional<Room> room = roomStore.findById(invitation.roomId());
      if (room.isPresent() && isJoinable(room.get())) {
        live.add(toLive(invitation, room.get()));
      } else {
        // Lazy prune: the room is gone or no longer joinable, so the invitation is dead weight.
        invitationStore.delete(inviteeUserId, invitation.roomId());
      }
    }
    return live;
  }

  /**
   * Accepts the invitation addressed to {@code inviteeUserId} for {@code roomId}: re-validates the
   * invitation and the room's liveness, performs the room join SERVER-SIDE using the room's stored
   * join token (read here, never exposed to the client), deletes the invitation, and returns the
   * join result. The inviter learns of the join through the existing {@code RoomJoinedEvent} {@link
   * RoomService#joinRoom} broadcasts — no new event is emitted.
   *
   * @param inviteeUserId the accepting caller's user id.
   * @param inviteeDisplayName the caller's display name, threaded into the joining {@link Player}
   *     so the game links to the account (feature 19).
   * @param roomId the room to join.
   * @return the {@link JoinedRoom} carrier so the controller can surface the joined room response.
   * @throws InvitationNotFoundException if no live invitation exists for the pair, or the room is
   *     no longer joinable (404).
   * @throws RoomFullException if the room filled in the meantime — a sibling invite was accepted
   *     first (409).
   */
  public JoinedRoom accept(UUID inviteeUserId, String inviteeDisplayName, String roomId) {
    Invitation invitation =
        invitationStore
            .find(inviteeUserId, roomId)
            .orElseThrow(() -> new InvitationNotFoundException(roomId));
    Room room = roomStore.findById(roomId).orElse(null);
    if (room == null || !isJoinable(room)) {
      // The invitation outlived its room (or the room is no longer joinable): prune and 404. A
      // genuinely-full room is handled below by joinRoom itself with a 409, distinguishing "your
      // invite is dead" from "someone beat you to the slot".
      invitationStore.delete(inviteeUserId, roomId);
      throw new InvitationNotFoundException(roomId);
    }
    // Server-side join with the token read from the live room — the token never reaches the client.
    JoinedRoom joined =
        roomService.joinRoom(roomId, inviteeDisplayName, inviteeUserId, room.joinToken());
    invitationStore.delete(inviteeUserId, roomId);
    log.info(
        "Invitation accepted: roomId={}, inviteeUserId={}, gameId={}",
        roomId,
        inviteeUserId,
        joined.game().id());
    return joined;
  }

  /**
   * Declines the invitation addressed to {@code inviteeUserId} for {@code roomId}: deletes it and
   * pushes an {@link InvitationDeclinedEvent} to the inviter's private queue so their UI clears.
   *
   * @param inviteeUserId the declining caller's user id.
   * @param roomId the room whose invitation is declined.
   * @throws InvitationNotFoundException if no invitation exists for the pair (404).
   */
  public void decline(UUID inviteeUserId, String roomId) {
    Invitation invitation =
        invitationStore
            .find(inviteeUserId, roomId)
            .orElseThrow(() -> new InvitationNotFoundException(roomId));
    invitationStore.delete(inviteeUserId, roomId);
    log.info("Invitation declined: roomId={}, inviteeUserId={}", roomId, inviteeUserId);
    pushToUser(
        invitation.inviterUserId(),
        new InvitationDeclinedEvent(roomId, inviteeUserId),
        "InvitationDeclinedEvent");
  }

  /**
   * Cancels an invitation the caller sent to {@code inviteeUserId} for {@code roomId}: validates
   * the caller is a member of the room, deletes the invitation, and pushes an {@link
   * InvitationCancelledEvent} to the invitee's private queue so their banner clears.
   *
   * @param inviterUserId the cancelling caller's user id.
   * @param roomId the room whose invitation is cancelled.
   * @param inviteeUserId the user the invitation was sent to.
   * @throws NotRoomMemberException if the caller is not a player of the room (403).
   * @throws InvitationNotFoundException if no invitation exists for the pair (404).
   */
  public void cancel(UUID inviterUserId, String roomId, UUID inviteeUserId) {
    Room room = roomStore.findById(roomId).orElse(null);
    if (room != null && !isMember(room, inviterUserId)) {
      throw new NotRoomMemberException(roomId);
    }
    if (invitationStore.find(inviteeUserId, roomId).isEmpty()) {
      throw new InvitationNotFoundException(roomId);
    }
    invitationStore.delete(inviteeUserId, roomId);
    log.info(
        "Invitation cancelled: roomId={}, inviterUserId={}, inviteeUserId={}",
        roomId,
        inviterUserId,
        inviteeUserId);
    pushToUser(inviteeUserId, new InvitationCancelledEvent(roomId), "InvitationCancelledEvent");
  }

  /** A room is joinable when it is still waiting, has a free slot, and carries a join token. */
  private static boolean isJoinable(Room room) {
    return room.status() == RoomStatus.WAITING_FOR_PLAYER
        && room.players().size() < 2
        && room.joinToken() != null;
  }

  /** Whether {@code userId} is the {@code userId} of one of the room's players. */
  private static boolean isMember(Room room, UUID userId) {
    return room.players().stream()
        .map(Player::userId)
        .anyMatch(playerUserId -> userId.equals(playerUserId));
  }

  /**
   * Projects a stored {@link Invitation} plus its live {@link Room} into a {@link LiveInvitation}.
   * The side the invitee would take is the opposite of the creator's side; the time control comes
   * from the live room — both derived, never stored, so they are never stale.
   */
  private static LiveInvitation toLive(Invitation invitation, Room room) {
    Side inviteeSide = room.creatorSide() == Side.WHITE ? Side.BLACK : Side.WHITE;
    return new LiveInvitation(
        invitation.roomId(),
        invitation.inviterUserId(),
        invitation.inviterDisplayName(),
        room.timeControl(),
        inviteeSide,
        invitation.createdAt());
  }

  /**
   * Best-effort push of an {@code InvitationEvent} to a user's private queue ({@code
   * /user/{userId}/queue/invitations}). Mirrors {@code RoomService.broadcastRoomJoinedEvent}: any
   * {@link RuntimeException} from the broker is logged at WARN and swallowed, and a success logs at
   * INFO. The REST list is the fallback for a missed push (e.g. an offline invitee).
   */
  private void pushToUser(UUID userId, Object event, String eventName) {
    try {
      messagingTemplate.convertAndSendToUser(userId.toString(), INVITATIONS_DESTINATION, event);
      log.info("Pushed {} to user queue: userId={}", eventName, userId);
    } catch (RuntimeException ex) {
      log.warn("Failed to push {} to user {}: {}", eventName, userId, ex.getMessage());
    }
  }

  /**
   * Carrier returned from {@link #listIncoming(UUID)}: a stored invitation augmented with the live
   * room's derived facts (the side the invitee would take, the time control). The controller maps
   * it to {@code InvitationResponse}.
   *
   * @param roomId the room the invitation targets.
   * @param inviterUserId the inviter's user id.
   * @param inviterDisplayName the inviter's display name.
   * @param timeControl the live room's clock, or {@code null} for untimed.
   * @param inviteeSide the side the invitee would take on accept.
   * @param createdAt when the invitation was created or last refreshed.
   */
  public record LiveInvitation(
      String roomId,
      UUID inviterUserId,
      String inviterDisplayName,
      TimeControl timeControl,
      Side inviteeSide,
      Instant createdAt) {}
}
