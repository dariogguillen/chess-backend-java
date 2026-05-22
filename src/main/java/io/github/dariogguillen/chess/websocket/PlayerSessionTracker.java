package io.github.dariogguillen.chess.websocket;

import io.github.dariogguillen.chess.config.DisconnectProperties;
import io.github.dariogguillen.chess.domain.Game;
import io.github.dariogguillen.chess.domain.Side;
import io.github.dariogguillen.chess.exception.GameNotFoundException;
import io.github.dariogguillen.chess.service.GameService;
import io.github.dariogguillen.chess.service.GracePeriodManager;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
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
 *       gameId))} association and cancel any pending grace-period timer for the pair. If the cancel
 *       actually removed a timer (the reconnect-within-grace path), broadcast a {@link
 *       PlayerReconnectedEvent} on {@code /topic/games/{gameId}} so the opponent's UI can clear the
 *       "reconnecting" banner.
 *   <li>On {@link SessionDisconnectEvent}: look up the association by session id, remove it, and if
 *       the game is still non-terminal start a grace-period timer for the pair and broadcast a
 *       {@link PlayerDisconnectedEvent} on {@code /topic/games/{gameId}} carrying the absolute
 *       grace-period deadline so the opponent's UI can render a countdown.
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
 * the same event. The new STOMP broadcasts also follow the {@code GameAbandonService.abandon}
 * pattern — wrapped in their own {@code try/catch + WARN log} so a broker-side failure does not
 * propagate past the event handler.
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
  private final SimpMessagingTemplate messagingTemplate;
  private final DisconnectProperties disconnectProperties;
  private final Clock clock;

  /** sessionId → the player and game this session is associated with. */
  private final Map<String, PlayerAssociation> sessions = new ConcurrentHashMap<>();

  public PlayerSessionTracker(
      GameService gameService,
      GracePeriodManager gracePeriodManager,
      SimpMessagingTemplate messagingTemplate,
      DisconnectProperties disconnectProperties,
      Clock clock) {
    this.gameService = gameService;
    this.gracePeriodManager = gracePeriodManager;
    this.messagingTemplate = messagingTemplate;
    this.disconnectProperties = disconnectProperties;
    this.clock = clock;
  }

  /**
   * Handles {@code SUBSCRIBE} frames. If the destination is a game topic and the {@code playerId}
   * header matches white or black of the game, records the association and cancels any pending
   * grace-period timer for the pair. When the cancel reports it actually removed a timer (the
   * reconnect-within-grace path), broadcasts a {@link PlayerReconnectedEvent} on the same topic so
   * the opponent's UI can clear the countdown banner.
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
      Game game = findGameOrNull(gameId);
      if (game == null) {
        return;
      }
      Side side = sideOfOrNull(game, playerId);
      if (side == null) {
        return;
      }
      sessions.put(sessionId, new PlayerAssociation(playerId, gameId));
      boolean cancelled = gracePeriodManager.cancelGracePeriod(playerId, gameId);
      if (cancelled) {
        broadcastReconnected(gameId, playerId, side);
      }
    } catch (RuntimeException ex) {
      log.warn("Failed to handle SessionSubscribeEvent: {}", ex.getMessage());
    }
  }

  /**
   * Handles session {@code DISCONNECT}. Looks up the association by session id and, if found,
   * starts a grace-period timer for the {@code (playerId, gameId)} pair — unless the game is
   * already in a terminal status, in which case the disconnect is a no-op (idempotency: a player
   * dropping after checkmate must not produce a ghost abandonment). When the timer is started, also
   * broadcasts a {@link PlayerDisconnectedEvent} carrying the absolute grace-period deadline so the
   * opponent's UI can render the countdown banner.
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
      Game game = findGameOrNull(association.gameId());
      if (game == null || game.status().isTerminal()) {
        log.debug(
            "Player disconnect on already-terminal or unknown game: playerId={}, gameId={}",
            association.playerId(),
            association.gameId());
        return;
      }
      Side side = sideOfOrNull(game, association.playerId());
      if (side == null) {
        // Defensive: the association was created from a SUBSCRIBE that already verified
        // membership, so a player whose id no longer matches white or black would mean the game
        // was replaced under us. Skip the broadcast rather than guess at a side.
        return;
      }
      gracePeriodManager.startGracePeriod(association.playerId(), association.gameId());
      Instant disconnectedAt = Instant.now(clock);
      Instant gracePeriodEndsAt = disconnectedAt.plus(disconnectProperties.gracePeriod());
      broadcastDisconnected(
          association.gameId(), association.playerId(), side, disconnectedAt, gracePeriodEndsAt);
    } catch (RuntimeException ex) {
      log.warn("Failed to handle SessionDisconnectEvent: {}", ex.getMessage());
    }
  }

  /**
   * Looks up the game by id, returning {@code null} if the game does not exist (e.g. a STOMP {@code
   * SUBSCRIBE} for a stale id).
   */
  private Game findGameOrNull(UUID gameId) {
    try {
      return gameService.findById(gameId);
    } catch (GameNotFoundException ex) {
      return null;
    }
  }

  /**
   * Returns the {@link Side} that the player occupies in the game, or {@code null} if the player is
   * neither white nor black of the game.
   */
  private static Side sideOfOrNull(Game game, UUID playerId) {
    if (playerId.equals(game.white().id())) {
      return Side.WHITE;
    }
    if (playerId.equals(game.black().id())) {
      return Side.BLACK;
    }
    return null;
  }

  /**
   * Broadcasts a {@link PlayerDisconnectedEvent} on {@code /topic/games/{gameId}}. Wrapped in
   * {@code try/catch + WARN log} mirroring {@code GameAbandonService.abandon} — a broker-side
   * failure must not propagate past the event handler.
   */
  private void broadcastDisconnected(
      UUID gameId, UUID playerId, Side side, Instant disconnectedAt, Instant gracePeriodEndsAt) {
    PlayerDisconnectedEvent event =
        new PlayerDisconnectedEvent(gameId, playerId, side, disconnectedAt, gracePeriodEndsAt);
    try {
      messagingTemplate.convertAndSend("/topic/games/" + gameId, event);
    } catch (RuntimeException ex) {
      log.warn(
          "Failed to broadcast PlayerDisconnectedEvent for game {}: {}", gameId, ex.getMessage());
    }
  }

  /**
   * Broadcasts a {@link PlayerReconnectedEvent} on {@code /topic/games/{gameId}}. Wrapped in {@code
   * try/catch + WARN log} mirroring {@code GameAbandonService.abandon}.
   */
  private void broadcastReconnected(UUID gameId, UUID playerId, Side side) {
    PlayerReconnectedEvent event =
        new PlayerReconnectedEvent(gameId, playerId, side, Instant.now(clock));
    try {
      messagingTemplate.convertAndSend("/topic/games/" + gameId, event);
    } catch (RuntimeException ex) {
      log.warn(
          "Failed to broadcast PlayerReconnectedEvent for game {}: {}", gameId, ex.getMessage());
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
