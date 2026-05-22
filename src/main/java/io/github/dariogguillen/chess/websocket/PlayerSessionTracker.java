package io.github.dariogguillen.chess.websocket;

import io.github.dariogguillen.chess.domain.Game;
import io.github.dariogguillen.chess.exception.GameNotFoundException;
import io.github.dariogguillen.chess.service.GameService;
import io.github.dariogguillen.chess.service.GracePeriodManager;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

/**
 * Maintains the association between STOMP sessions and the player who owns them, so that a session
 * {@code DISCONNECT} can be turned into a "start the abandon grace period for player P of game G"
 * decision. Mirrors {@link ViewerCountTracker}'s structural pattern — same {@code @EventListener}
 * mechanism, same {@code playerId} native header convention, same tolerant UUID parsing — but
 * tracks the <strong>two players</strong> of a game rather than the spectators. The two trackers
 * are orthogonal: a session is registered with the {@link ViewerCountTracker} if and only if its
 * subscriber is <em>not</em> a player, and with this tracker if and only if its subscriber
 * <em>is</em> a player. There is no overlap.
 *
 * <p>Lifecycle:
 *
 * <ul>
 *   <li>On {@link SessionSubscribeEvent} for {@code /topic/games/{gameId}} with a {@code playerId}
 *       header that matches white or black of the game: record the {@code (sessionId → (playerId,
 *       gameId))} association and cancel any pending grace-period timer for the pair (the reconnect
 *       path).
 *   <li>On {@link SessionDisconnectEvent}: look up the association by session id, remove it, and if
 *       the game is still non-terminal start a grace-period timer for the pair.
 * </ul>
 *
 * <p>The trust model is the same as {@link ViewerCountTracker}: the {@code playerId} header is
 * taken at face value because the project has no authentication yet. A malicious client could forge
 * another player's id to start (or block) a grace timer on their behalf. This is a deliberate
 * portfolio-level trade-off documented in {@code docs/architecture.md}; a future auth feature
 * replaces "trust" with "verify" without changing the public surface of this class.
 *
 * <p>Concurrency: the single {@code sessions} map is a {@link ConcurrentHashMap}; reads on
 * disconnect are removes, so there is no read-write race within this class. The downstream race (a
 * near-simultaneous reconnect cancelling the same timer the disconnect path is about to fire) is
 * handled by {@link GracePeriodManager}'s per-key locking.
 *
 * <p>Failure mode mirrors {@link ViewerCountTracker}: each event handler wraps its body in a
 * top-level catch so an unhandled exception in one listener cannot poison downstream listeners on
 * the same event.
 */
@Component
public class PlayerSessionTracker {

  private static final Logger log = LoggerFactory.getLogger(PlayerSessionTracker.class);

  /**
   * Matches game topics like {@code /topic/games/{gameId}}, anchored at start AND end so that
   * sub-topics such as {@code /topic/games/{gameId}/viewers} do not match — same regex {@link
   * ViewerCountTracker} uses for the same reason.
   */
  private static final Pattern GAME_TOPIC_PATTERN = Pattern.compile("^/topic/games/([^/]+)$");

  private final GameService gameService;
  private final GracePeriodManager gracePeriodManager;

  /** sessionId → the player and game this session is associated with. */
  private final Map<String, PlayerAssociation> sessions = new ConcurrentHashMap<>();

  public PlayerSessionTracker(GameService gameService, GracePeriodManager gracePeriodManager) {
    this.gameService = gameService;
    this.gracePeriodManager = gracePeriodManager;
  }

  /**
   * Handles {@code SUBSCRIBE} frames. If the destination is a game topic and the {@code playerId}
   * header matches white or black of the game, records the association and cancels any pending
   * grace-period timer for the pair (the reconnect path).
   */
  @EventListener
  public void onSubscribe(SessionSubscribeEvent event) {
    try {
      StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
      String destination = accessor.getDestination();
      String sessionId = accessor.getSessionId();
      String playerIdHeader = accessor.getFirstNativeHeader("playerId");

      if (destination == null || sessionId == null || playerIdHeader == null) {
        return;
      }
      Matcher matcher = GAME_TOPIC_PATTERN.matcher(destination);
      if (!matcher.matches()) {
        return;
      }
      UUID gameId = parseUuidOrNull(matcher.group(1));
      UUID playerId = parseUuidOrNull(playerIdHeader);
      if (gameId == null || playerId == null) {
        return;
      }
      if (!isPlayerOfGame(gameId, playerId)) {
        return;
      }
      sessions.put(sessionId, new PlayerAssociation(playerId, gameId));
      gracePeriodManager.cancelGracePeriod(playerId, gameId);
    } catch (RuntimeException ex) {
      log.warn("Failed to handle SessionSubscribeEvent: {}", ex.getMessage());
    }
  }

  /**
   * Handles session {@code DISCONNECT}. Looks up the association by session id and, if found,
   * starts a grace-period timer for the {@code (playerId, gameId)} pair — unless the game is
   * already in a terminal status, in which case the disconnect is a no-op (idempotency: a player
   * dropping after checkmate must not produce a ghost abandonment).
   */
  @EventListener
  public void onDisconnect(SessionDisconnectEvent event) {
    try {
      StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
      String sessionId = accessor.getSessionId();
      if (sessionId == null) {
        return;
      }
      PlayerAssociation association = sessions.remove(sessionId);
      if (association == null) {
        return;
      }
      if (isGameTerminal(association.gameId())) {
        log.debug(
            "Player disconnect on already-terminal game: playerId={}, gameId={}",
            association.playerId(),
            association.gameId());
        return;
      }
      gracePeriodManager.startGracePeriod(association.playerId(), association.gameId());
    } catch (RuntimeException ex) {
      log.warn("Failed to handle SessionDisconnectEvent: {}", ex.getMessage());
    }
  }

  /**
   * Returns {@code true} if {@code playerId} matches white or black of the game with {@code
   * gameId}. If the game does not exist (e.g. a STOMP {@code SUBSCRIBE} for a stale id), returns
   * {@code false} — no association is recorded, no timer will ever be scheduled.
   */
  private boolean isPlayerOfGame(UUID gameId, UUID playerId) {
    try {
      Game game = gameService.findById(gameId);
      return playerId.equals(game.white().id()) || playerId.equals(game.black().id());
    } catch (GameNotFoundException ex) {
      return false;
    }
  }

  /**
   * Returns {@code true} if the game with {@code gameId} exists and is in a terminal status, so the
   * disconnect path can short-circuit without starting a grace timer. A non-existent game also
   * returns {@code true} — there is nothing to abandon, the disconnect is harmless. Any other
   * lookup failure returns {@code false} (we err on the side of starting the timer; the abandon
   * path itself is idempotent).
   */
  private boolean isGameTerminal(UUID gameId) {
    try {
      return gameService.findById(gameId).status().isTerminal();
    } catch (GameNotFoundException ex) {
      return true;
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

  /** Records the player and game a STOMP session is associated with. */
  private record PlayerAssociation(UUID playerId, UUID gameId) {}
}
