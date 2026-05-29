package io.github.dariogguillen.chess.websocket;

import io.github.dariogguillen.chess.domain.Player;
import io.github.dariogguillen.chess.domain.Room;
import io.github.dariogguillen.chess.service.RoomService;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

/**
 * Maintains a per-room spectator count and broadcasts it to {@code /topic/rooms/{roomId}/viewers}
 * whenever it changes. A "spectator" is any STOMP session subscribed to {@code
 * /topic/rooms/{roomId}} that did not declare a {@code playerId} native header matching one of the
 * players of the room.
 *
 * <p>The count is keyed on the <strong>room</strong> rather than the game (feature 22.5, {@code
 * spectators-in-room}). The room exists from {@code WAITING_FOR_PLAYER} onward — well before a game
 * is created on the second player's join — so a creator can have friends watching while the room is
 * still in the lobby, and the count stays stable across the {@code WAITING_FOR_PLAYER → ACTIVE}
 * transition (no reset, no double-count). The game-keyed {@code /topic/games/{gameId}/viewers}
 * topic from feature 6.5 is retired: this tracker no longer listens to {@code /topic/games/*}. Game
 * state ({@code MoveEvent} and the {@code GameStateEvent} family) stays on {@code
 * /topic/games/{gameId}}, untouched.
 *
 * <p>Subscribes to three Spring-published STOMP session events:
 *
 * <ul>
 *   <li>{@link SessionSubscribeEvent} — increment if the destination is a room topic and the
 *       subscriber is not a player.
 *   <li>{@link SessionUnsubscribeEvent} — decrement if this was the last subscription of the
 *       session to the room.
 *   <li>{@link SessionDisconnectEvent} — decrement for every room the session was viewing.
 * </ul>
 *
 * <p>Identity model: the {@code playerId} header is trusted at face value. There is no
 * authentication wired into this path today, so the server cannot verify the claim — a malicious
 * client could omit the header and inflate the count, or send a forged header and exclude itself.
 * This is a deliberate portfolio-level trade-off documented in {@code docs/architecture.md}; a
 * future auth feature would replace "trust" with "verify" without changing this class's public
 * surface.
 *
 * <p>The {@code roomId} captured from the destination is the room's six-character short code and is
 * used verbatim as the map key — it is not a UUID and is not parsed as one. The {@code playerId}
 * header is parsed as a {@link UUID} (it identifies a {@link Player}); a header that is not a valid
 * UUID is treated as "no match" — the subscription is counted as a viewer. A subscribe to a room
 * that does not exist is also counted as a viewer (tolerant — mirrors the old game behaviour: the
 * client sees {@code count: 1} for a room it should have validated via {@code GET /api/rooms/{id}}
 * before subscribing).
 *
 * <p>Concurrency: the three internal maps are {@link ConcurrentHashMap}s, with set-valued entries
 * backed by {@link ConcurrentHashMap#newKeySet()}. The "is this the last subscription of the
 * session for this room?" check on unsubscribe is racy under tight interleavings but the count
 * converges to the correct value via the next event. For the spectator UX, eventual consistency is
 * acceptable.
 *
 * <p>Broadcast failure mode mirrors {@code GameService.broadcastMoveEvent}: any {@link
 * RuntimeException} from {@link SimpMessagingTemplate#convertAndSend} is caught and logged at
 * {@code WARN}; not rethrown. Each event handler also wraps its body in a top-level catch so an
 * unhandled exception in one listener cannot affect downstream listeners on the same event.
 *
 * <p>Each successful {@link ViewerCountEvent} broadcast emits a single INFO log line carrying the
 * destination and the current {@code count}; the failure path stays on WARN.
 */
@Component
public class ViewerCountTracker {

  private static final Logger log = LoggerFactory.getLogger(ViewerCountTracker.class);

  /**
   * Matches room topics like {@code /topic/rooms/{roomId}}, anchored at start AND end so that
   * sub-topics such as {@code /topic/rooms/{roomId}/viewers} do not match. Viewer-count subscribers
   * should not themselves bump the count.
   */
  private static final Pattern ROOM_TOPIC_PATTERN = Pattern.compile("^/topic/rooms/([^/]+)$");

  private final RoomService roomService;
  private final SimpMessagingTemplate messagingTemplate;

  // roomId -> set of session ids currently viewing the room (excluding players).
  private final Map<String, Set<String>> sessionsByRoom = new ConcurrentHashMap<>();

  // sessionId -> set of room ids the session is currently viewing.
  private final Map<String, Set<String>> roomsBySession = new ConcurrentHashMap<>();

  // subscriptionId -> (sessionId, roomId). Required because SessionUnsubscribeEvent does not carry
  // the destination, only the subscription id; we recover the room from the subscription id here.
  private final Map<String, RoomSubscription> subscriptionToRoom = new ConcurrentHashMap<>();

  public ViewerCountTracker(RoomService roomService, SimpMessagingTemplate messagingTemplate) {
    this.roomService = roomService;
    this.messagingTemplate = messagingTemplate;
  }

  /**
   * Handles {@code SUBSCRIBE} frames. If the destination is a room topic and the subscriber is not
   * a player of the room, records the subscription and broadcasts the new count.
   */
  @EventListener
  public void onSubscribe(SessionSubscribeEvent event) {
    try {
      StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
      String destination = accessor.getDestination();
      String sessionId = accessor.getSessionId();
      String subscriptionId = accessor.getSubscriptionId();
      String playerIdHeader = accessor.getFirstNativeHeader("playerId");

      if (destination == null || sessionId == null || subscriptionId == null) {
        return;
      }
      Matcher matcher = ROOM_TOPIC_PATTERN.matcher(destination);
      if (!matcher.matches()) {
        return;
      }
      String roomId = matcher.group(1);
      UUID playerId = playerIdHeader == null ? null : parseUuidOrNull(playerIdHeader);

      if (playerId != null && isPlayerOfRoom(roomId, playerId)) {
        return;
      }

      subscriptionToRoom.put(subscriptionId, new RoomSubscription(sessionId, roomId));
      sessionsByRoom.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet()).add(sessionId);
      roomsBySession.computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet()).add(roomId);

      int newCount = sessionsByRoom.get(roomId).size();
      broadcast(roomId, newCount);
    } catch (RuntimeException ex) {
      log.warn("Failed to handle SessionSubscribeEvent: {}", ex.getMessage());
    }
  }

  /**
   * Handles {@code UNSUBSCRIBE} frames. The frame carries only the subscription id; we look up the
   * (session, room) pair recorded on subscribe. If this was the last subscription of the session to
   * the room, the session leaves the spectator set and a new count is broadcast.
   */
  @EventListener
  public void onUnsubscribe(SessionUnsubscribeEvent event) {
    try {
      StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
      String subscriptionId = accessor.getSubscriptionId();
      if (subscriptionId == null) {
        return;
      }

      RoomSubscription rs = subscriptionToRoom.remove(subscriptionId);
      if (rs == null) {
        return;
      }

      boolean stillSubscribed =
          subscriptionToRoom.values().stream()
              .anyMatch(
                  other ->
                      other.sessionId().equals(rs.sessionId())
                          && other.roomId().equals(rs.roomId()));
      if (stillSubscribed) {
        return;
      }

      Set<String> sessions = sessionsByRoom.get(rs.roomId());
      boolean removed = sessions != null && sessions.remove(rs.sessionId());

      Set<String> rooms = roomsBySession.get(rs.sessionId());
      if (rooms != null) {
        rooms.remove(rs.roomId());
        if (rooms.isEmpty()) {
          roomsBySession.remove(rs.sessionId());
        }
      }

      if (removed) {
        int newCount = sessions.size();
        broadcast(rs.roomId(), newCount);
      }
    } catch (RuntimeException ex) {
      log.warn("Failed to handle SessionUnsubscribeEvent: {}", ex.getMessage());
    }
  }

  /**
   * Handles session {@code DISCONNECT}. Removes the session from every room it was viewing and
   * broadcasts the new count for each. Also clears any stale {@code subscriptionToRoom} entries.
   */
  @EventListener
  public void onDisconnect(SessionDisconnectEvent event) {
    try {
      StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
      String sessionId = accessor.getSessionId();
      if (sessionId == null) {
        return;
      }

      Set<String> rooms = roomsBySession.remove(sessionId);
      if (rooms == null) {
        return;
      }

      for (String roomId : rooms) {
        Set<String> sessions = sessionsByRoom.get(roomId);
        if (sessions != null && sessions.remove(sessionId)) {
          broadcast(roomId, sessions.size());
        }
      }

      subscriptionToRoom.entrySet().removeIf(e -> e.getValue().sessionId().equals(sessionId));
    } catch (RuntimeException ex) {
      log.warn("Failed to handle SessionDisconnectEvent: {}", ex.getMessage());
    }
  }

  /**
   * Returns true if {@code playerId} matches one of the players of the room with {@code roomId}. If
   * the room does not exist, returns false — a subscriber to a non-existent room is treated as a
   * non-player (the broadcast still goes out; the client sees {@code count: 1} for a room it should
   * have validated before subscribing).
   */
  private boolean isPlayerOfRoom(String roomId, UUID playerId) {
    Optional<Room> room = roomService.findById(roomId);
    if (room.isEmpty()) {
      return false;
    }
    for (Player player : room.get().players()) {
      if (playerId.equals(player.id())) {
        return true;
      }
    }
    return false;
  }

  private void broadcast(String roomId, int count) {
    try {
      messagingTemplate.convertAndSend(
          "/topic/rooms/" + roomId + "/viewers", new ViewerCountEvent(roomId, count));
      log.info(
          "Broadcasted ViewerCountEvent to {}: count={}",
          "/topic/rooms/" + roomId + "/viewers",
          count);
    } catch (RuntimeException ex) {
      log.warn("Failed to broadcast ViewerCountEvent for room {}: {}", roomId, ex.getMessage());
    }
  }

  /**
   * Parses {@code value} as a {@link UUID}, returning {@code null} if it is not a valid UUID. Used
   * at the STOMP boundary where input comes from untrusted clients — a {@code playerId} header that
   * is not a UUID is treated as "not a known player" rather than as a fatal error.
   */
  private static UUID parseUuidOrNull(String value) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  /** Records the (session, room) pair a subscription is bound to, keyed by subscription id. */
  private record RoomSubscription(String sessionId, String roomId) {}
}
