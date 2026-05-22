package io.github.dariogguillen.chess.websocket;

import io.github.dariogguillen.chess.domain.Game;
import io.github.dariogguillen.chess.exception.GameNotFoundException;
import io.github.dariogguillen.chess.service.GameService;
import java.util.Map;
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
 * Maintains a per-game spectator count and broadcasts it to {@code /topic/games/{gameId}/viewers}
 * whenever it changes. A "spectator" is any STOMP session subscribed to {@code
 * /topic/games/{gameId}} that did not declare a {@code playerId} native header matching one of the
 * two players of the game.
 *
 * <p>Subscribes to three Spring-published STOMP session events:
 *
 * <ul>
 *   <li>{@link SessionSubscribeEvent} — increment if the destination is a game topic and the
 *       subscriber is not a player.
 *   <li>{@link SessionUnsubscribeEvent} — decrement if this was the last subscription of the
 *       session to the game.
 *   <li>{@link SessionDisconnectEvent} — decrement for every game the session was viewing.
 * </ul>
 *
 * <p>Identity model: the {@code playerId} header is trusted at face value. There is no
 * authentication today, so the server cannot verify the claim — a malicious client could omit the
 * header and inflate the count, or send a forged header and exclude itself. This is a deliberate
 * portfolio-level trade-off documented in {@code docs/architecture.md}; a future auth feature would
 * replace "trust" with "verify" without changing this class's public surface.
 *
 * <p>The {@code gameId} captured from the destination and the {@code playerId} header are both
 * parsed as {@link UUID}. A malformed value (a topic for a non-UUID id, or a header that is not a
 * UUID) is treated as "no match" — the subscription is ignored for tracking purposes. This is the
 * least-surprising failure mode for a non-authenticated broadcast layer: client-side bugs do not
 * crash the broadcast handler.
 *
 * <p>Concurrency: the three internal maps are {@link ConcurrentHashMap}s, with set-valued entries
 * backed by {@link ConcurrentHashMap#newKeySet()}. The "is this the last subscription of the
 * session for this game?" check on unsubscribe is racy under tight interleavings but the count
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
   * Matches game topics like {@code /topic/games/{gameId}}, anchored at start AND end so that
   * sub-topics such as {@code /topic/games/{gameId}/viewers} do not match. Viewer-count subscribers
   * should not themselves bump the count.
   */
  private static final Pattern GAME_TOPIC_PATTERN = Pattern.compile("^/topic/games/([^/]+)$");

  private final GameService gameService;
  private final SimpMessagingTemplate messagingTemplate;

  // gameId -> set of session ids currently viewing the game (excluding players).
  private final Map<UUID, Set<String>> sessionsByGame = new ConcurrentHashMap<>();

  // sessionId -> set of game ids the session is currently viewing.
  private final Map<String, Set<UUID>> gamesBySession = new ConcurrentHashMap<>();

  // subscriptionId -> (sessionId, gameId). Required because SessionUnsubscribeEvent does not carry
  // the destination, only the subscription id; we recover the game from the subscription id here.
  private final Map<String, GameSubscription> subscriptionToGame = new ConcurrentHashMap<>();

  public ViewerCountTracker(GameService gameService, SimpMessagingTemplate messagingTemplate) {
    this.gameService = gameService;
    this.messagingTemplate = messagingTemplate;
  }

  /**
   * Handles {@code SUBSCRIBE} frames. If the destination is a game topic and the subscriber is not
   * a player of the game, records the subscription and broadcasts the new count.
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
      Matcher matcher = GAME_TOPIC_PATTERN.matcher(destination);
      if (!matcher.matches()) {
        return;
      }
      UUID gameId = parseUuidOrNull(matcher.group(1));
      if (gameId == null) {
        return;
      }
      UUID playerId = playerIdHeader == null ? null : parseUuidOrNull(playerIdHeader);

      if (playerId != null && isPlayerOfGame(gameId, playerId)) {
        return;
      }

      subscriptionToGame.put(subscriptionId, new GameSubscription(sessionId, gameId));
      sessionsByGame.computeIfAbsent(gameId, k -> ConcurrentHashMap.newKeySet()).add(sessionId);
      gamesBySession.computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet()).add(gameId);

      int newCount = sessionsByGame.get(gameId).size();
      broadcast(gameId, newCount);
    } catch (RuntimeException ex) {
      log.warn("Failed to handle SessionSubscribeEvent: {}", ex.getMessage());
    }
  }

  /**
   * Handles {@code UNSUBSCRIBE} frames. The frame carries only the subscription id; we look up the
   * (session, game) pair recorded on subscribe. If this was the last subscription of the session to
   * the game, the session leaves the spectator set and a new count is broadcast.
   */
  @EventListener
  public void onUnsubscribe(SessionUnsubscribeEvent event) {
    try {
      StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
      String subscriptionId = accessor.getSubscriptionId();
      if (subscriptionId == null) {
        return;
      }

      GameSubscription gs = subscriptionToGame.remove(subscriptionId);
      if (gs == null) {
        return;
      }

      boolean stillSubscribed =
          subscriptionToGame.values().stream()
              .anyMatch(
                  other ->
                      other.sessionId().equals(gs.sessionId())
                          && other.gameId().equals(gs.gameId()));
      if (stillSubscribed) {
        return;
      }

      Set<String> sessions = sessionsByGame.get(gs.gameId());
      boolean removed = sessions != null && sessions.remove(gs.sessionId());

      Set<UUID> games = gamesBySession.get(gs.sessionId());
      if (games != null) {
        games.remove(gs.gameId());
        if (games.isEmpty()) {
          gamesBySession.remove(gs.sessionId());
        }
      }

      if (removed) {
        int newCount = sessions.size();
        broadcast(gs.gameId(), newCount);
      }
    } catch (RuntimeException ex) {
      log.warn("Failed to handle SessionUnsubscribeEvent: {}", ex.getMessage());
    }
  }

  /**
   * Handles session {@code DISCONNECT}. Removes the session from every game it was viewing and
   * broadcasts the new count for each. Also clears any stale {@code subscriptionToGame} entries.
   */
  @EventListener
  public void onDisconnect(SessionDisconnectEvent event) {
    try {
      StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
      String sessionId = accessor.getSessionId();
      if (sessionId == null) {
        return;
      }

      Set<UUID> games = gamesBySession.remove(sessionId);
      if (games == null) {
        return;
      }

      for (UUID gameId : games) {
        Set<String> sessions = sessionsByGame.get(gameId);
        if (sessions != null && sessions.remove(sessionId)) {
          broadcast(gameId, sessions.size());
        }
      }

      subscriptionToGame.entrySet().removeIf(e -> e.getValue().sessionId().equals(sessionId));
    } catch (RuntimeException ex) {
      log.warn("Failed to handle SessionDisconnectEvent: {}", ex.getMessage());
    }
  }

  /**
   * Returns true if {@code playerId} matches white or black of the game with {@code gameId}. If the
   * game does not exist, returns false — a subscriber to a non-existent game is treated as a
   * non-player (the broadcast still goes out; the client sees {@code count: 1} for a game it should
   * have validated before subscribing).
   */
  private boolean isPlayerOfGame(UUID gameId, UUID playerId) {
    try {
      Game game = gameService.findById(gameId);
      return playerId.equals(game.white().id()) || playerId.equals(game.black().id());
    } catch (GameNotFoundException ex) {
      return false;
    }
  }

  private void broadcast(UUID gameId, int count) {
    try {
      messagingTemplate.convertAndSend(
          "/topic/games/" + gameId + "/viewers", new ViewerCountEvent(gameId, count));
      log.info(
          "Broadcasted ViewerCountEvent to {}: count={}",
          "/topic/games/" + gameId + "/viewers",
          count);
    } catch (RuntimeException ex) {
      log.warn("Failed to broadcast ViewerCountEvent for game {}: {}", gameId, ex.getMessage());
    }
  }

  /**
   * Parses {@code value} as a {@link UUID}, returning {@code null} if it is not a valid UUID. Used
   * at the STOMP boundary where input comes from untrusted clients — a non-UUID destination segment
   * or {@code playerId} header is treated as "not a known game / player" rather than as a fatal
   * error.
   */
  private static UUID parseUuidOrNull(String value) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  /** Records the (session, game) pair a subscription is bound to, keyed by subscription id. */
  private record GameSubscription(String sessionId, UUID gameId) {}
}
