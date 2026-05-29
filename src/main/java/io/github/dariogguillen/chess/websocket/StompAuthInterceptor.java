package io.github.dariogguillen.chess.websocket;

import io.github.dariogguillen.chess.config.security.JwtVerifier;
import io.github.dariogguillen.chess.domain.Game;
import io.github.dariogguillen.chess.domain.Player;
import io.github.dariogguillen.chess.domain.User;
import io.github.dariogguillen.chess.persistence.UserRepository;
import io.github.dariogguillen.chess.service.GameStore;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * STOMP {@link ChannelInterceptor} that strengthens the WebSocket trust model added by feature 20
 * ({@code auth-stomp-trust}). Sits at the head of the client-inbound channel and runs before any
 * {@code @MessageMapping} controller or downstream session listener ({@link PlayerSessionTracker},
 * {@link ViewerCountTracker}) sees a frame.
 *
 * <p>The interceptor is the load-bearing piece for the auth bundle's bundle-level decision 7:
 * <em>"anonymous STOMP keeps working; JWT only used to strengthen identity, never to gate
 * access."</em> It never rejects a CONNECT — a missing, malformed, expired, or differently-signed
 * JWT all leave the session anonymous; the existing guest-play tests pin this behaviour unchanged.
 * What it does block is identity spoofing on SEND and SUBSCRIBE frames that explicitly carry a
 * {@code playerId} native header.
 *
 * <h2>Two-phase contract</h2>
 *
 * <h3>Phase 1 — CONNECT</h3>
 *
 * Reads the native {@code Authorization} header (the {@code Authorization: Bearer <jwt>} the
 * WebSocket handshake surfaced as a STOMP native header). If present and the JWT verifies via the
 * shared {@link JwtVerifier}, the {@code sub} claim is parsed as a {@link UUID}, the {@link User}
 * is loaded from {@link UserRepository}, and the session's {@link Principal} is set to a {@link
 * StompPrincipal} that carries both the user id and the user instance. The user id is also stored
 * on the session attributes under {@link #SESSION_USER_ID_ATTR} so downstream code that does not
 * have a {@code Principal} parameter can still read it. All failure modes — no header, non-Bearer
 * scheme, malformed token, expired token, wrong signature, missing user, non-UUID subject — leave
 * the session anonymous and emit a single DEBUG log line. DEBUG (not WARN) is deliberate: an
 * authenticated frontend that ships before this feature reaches production will routinely connect
 * without a header, and that is normal traffic, not noise to escalate.
 *
 * <h3>Phase 2 — SEND / SUBSCRIBE (identity-spoof prevention)</h3>
 *
 * Inspects the native {@code playerId} header. If the frame does not carry one, the interceptor
 * passes the message through unchanged — the spectator and pure-subscribe paths are deliberately
 * untouched (this is the regression pin for {@code RoomLifecycleIT} / {@code GameWebSocketIT},
 * which subscribe to game topics without a {@code playerId}). When the header IS present, the
 * interceptor enforces:
 *
 * <ul>
 *   <li><strong>Authenticated session, claim references a real game's player</strong> (the
 *       destination matches {@code /topic/games/{gameId}} or {@code /app/games/{gameId}/...} and
 *       the claimed {@code playerId} is white or black of that game): the player's {@link
 *       Player#userId()} must equal the session's authenticated user id. Mismatch → ERROR frame +
 *       drop. This is the canonical "A claims to be B's player on B's game" spoof.
 *   <li><strong>Authenticated session, claim does not reference an inspectable game</strong> (no
 *       parseable {@code gameId} in the destination, or the claimed playerId is not a player of the
 *       looked-up game): pin-on-first-use. The first claim pins the session's {@link
 *       #SESSION_PINNED_PLAYER_ID_ATTR}; subsequent frames whose claim mismatches the pin are
 *       rejected.
 *   <li><strong>Anonymous session:</strong> pure pin-on-first-use. The first SUBSCRIBE or SEND that
 *       carries a {@code playerId} claim pins that id on the session under {@link
 *       #SESSION_PINNED_PLAYER_ID_ATTR}. Subsequent frames whose claim does not match the pin are
 *       rejected the same way as the authenticated mismatch — ERROR frame + drop, session stays
 *       open. The pin is per-session, not global, so two different sessions can each pin different
 *       player ids.
 *   <li><strong>No {@code playerId} header:</strong> pass-through. The interceptor only validates
 *       claims; it does not require them.
 * </ul>
 *
 * <h3>Other frame types</h3>
 *
 * {@code DISCONNECT}, {@code UNSUBSCRIBE}, {@code ACK}, {@code NACK}, etc. pass through unchanged.
 * The interceptor is interested only in CONNECT, SEND, and SUBSCRIBE.
 *
 * <h2>Why ERROR-not-disconnect</h2>
 *
 * A STOMP ERROR frame sent back through the same channel allows the client to observe the rejection
 * without losing the connection. A buggy or compromised client can correct itself and retry on the
 * same session. Forcing a disconnect would interact badly with the disconnect handling layer
 * (feature 11) — a spoof attempt would inadvertently trigger an opponent's grace timer, which is
 * the opposite of what this feature is for.
 *
 * <h2>Log hygiene</h2>
 *
 * No PII (no email, no JWT {@code sub}, no user id, no raw token) appears in any log line. The
 * generic strings ("STOMP CONNECT carried invalid JWT", "STOMP frame rejected: claimed playerId
 * mismatch") match the no-PII posture established by features 17 and 18.
 */
@Component
public class StompAuthInterceptor implements ChannelInterceptor {

  private static final Logger log = LoggerFactory.getLogger(StompAuthInterceptor.class);
  private static final String BEARER_PREFIX = "Bearer ";
  private static final String PLAYER_ID_HEADER = "playerId";

  /**
   * Matches game-scoped destinations: the canonical subscribe target {@code /topic/games/{gameId}}
   * and the application-routed {@code /app/games/{gameId}/...} prefix used by future SEND handlers.
   * Anchored at the start; the trailing segment is captured loosely so a future SEND destination
   * like {@code /app/games/{gameId}/moves} matches the same way as {@code /topic/games/{gameId}}.
   */
  private static final Pattern GAME_DESTINATION_PATTERN =
      Pattern.compile("^/(?:topic|app)/games/([^/]+)(?:/.*)?$");

  /** Session-attribute key under which the authenticated user's id is stored on CONNECT. */
  static final String SESSION_USER_ID_ATTR = "authenticatedUserId";

  /**
   * Session-attribute key under which a session's pinned player id lives once the first {@code
   * playerId}-bearing frame that did not surface a game-lookup answer has been processed.
   * Pin-on-first-use anchors the per-session spoof check for both authenticated and anonymous
   * sessions in the destinations where game lookup is not available.
   */
  static final String SESSION_PINNED_PLAYER_ID_ATTR = "pinnedPlayerId";

  private final JwtVerifier jwtVerifier;
  private final UserRepository users;
  private final GameStore gameStore;
  private final MessageChannel clientOutboundChannel;

  public StompAuthInterceptor(
      JwtVerifier jwtVerifier,
      UserRepository users,
      GameStore gameStore,
      @Lazy @Qualifier("clientOutboundChannel") MessageChannel clientOutboundChannel) {
    this.jwtVerifier = jwtVerifier;
    this.users = users;
    this.gameStore = gameStore;
    this.clientOutboundChannel = clientOutboundChannel;
  }

  @Override
  public Message<?> preSend(Message<?> message, MessageChannel channel) {
    StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
    StompCommand command = accessor.getCommand();
    if (command == null) {
      return message;
    }
    return switch (command) {
      case CONNECT -> handleConnect(message, accessor);
      case SEND, SUBSCRIBE -> handleSendOrSubscribe(message, accessor);
      default -> message;
    };
  }

  /**
   * CONNECT-phase handler. Attempts to identify the session from the {@code Authorization} native
   * header. On any failure mode the session stays anonymous — the CONNECT itself is never rejected.
   * Returns the (possibly identity-augmented) message so the broker proceeds to send back the
   * CONNECTED frame.
   */
  private Message<?> handleConnect(Message<?> message, StompHeaderAccessor accessor) {
    Optional<String> bearer = extractBearer(accessor);
    if (bearer.isEmpty()) {
      // Anonymous CONNECT — the existing guest-play surface. No log line; this is normal traffic.
      return message;
    }
    Optional<User> resolved = tryResolveUser(bearer.get());
    if (resolved.isEmpty()) {
      // Bad / expired / forged JWT, or a stale subject. DEBUG because malformed JWTs on
      // first-deploy
      // day from an unmigrated frontend are routine — not a security event to escalate to WARN.
      log.debug("STOMP CONNECT carried invalid JWT; session staying anonymous");
      return message;
    }
    User user = resolved.get();
    StompPrincipal principal = new StompPrincipal(user.getId(), user);
    accessor.setUser(principal);
    Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
    if (sessionAttributes != null) {
      sessionAttributes.put(SESSION_USER_ID_ATTR, user.getId());
    }
    // Mutating the accessor in place propagates because the message is rebuilt from it below; the
    // alternative is to copy the message via MessageBuilder. setUser writes to the underlying
    // MessageHeaders map, which is the canonical pattern for identity attachment on CONNECT.
    return MessageBuilder.createMessage(message.getPayload(), accessor.getMessageHeaders());
  }

  /**
   * SEND / SUBSCRIBE-phase handler. Enforces the identity-spoof rules described in the class
   * JavaDoc. Returns the original message on pass-through, or {@code null} (and emits an ERROR
   * frame on the channel) when the claim is rejected.
   */
  private Message<?> handleSendOrSubscribe(Message<?> message, StompHeaderAccessor accessor) {
    String claimedRaw = accessor.getFirstNativeHeader(PLAYER_ID_HEADER);
    if (claimedRaw == null) {
      return message;
    }
    UUID claimed = parseUuidOrNull(claimedRaw);
    if (claimed == null) {
      // A malformed playerId header is treated as a spoof — the same as a mismatched one. The
      // alternative (silent pass-through) would let buggy clients send "abc123" and bypass the
      // check entirely.
      return rejectWithError(accessor, "Invalid playerId header");
    }

    Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
    UUID authenticatedUserId =
        sessionAttributes == null ? null : (UUID) sessionAttributes.get(SESSION_USER_ID_ATTR);

    // Try the strongest check first: if the destination targets a game we can inspect, verify the
    // claimed playerId against the game's player rows. Only valuable when the session is
    // authenticated — for anonymous sessions there is no user-side anchor to compare against.
    if (authenticatedUserId != null) {
      Optional<UUID> gameIdFromDestination =
          extractGameIdFromDestination(accessor.getDestination());
      if (gameIdFromDestination.isPresent()) {
        Game game = findGameOrNull(gameIdFromDestination.get());
        if (game != null) {
          Player matched = matchPlayerOrNull(game, claimed);
          if (matched != null) {
            if (!authenticatedUserId.equals(matched.userId())) {
              log.debug(
                  "STOMP frame rejected: authenticated session claimed a player owned by a different user");
              return rejectWithError(
                  accessor, "playerId does not belong to the authenticated user");
            }
            // Authenticated user IS the owner of the claimed player. Pass through; no need to pin.
            return message;
          }
        }
      }
    }

    // Fall back to pin-on-first-use for both authenticated and anonymous sessions. This covers the
    // generic claim case where the destination is not game-scoped (or the lookup did not match a
    // player) — the session may not switch identity mid-stream.
    if (sessionAttributes == null) {
      // No session attributes map (defensive — the broker normally provides one). Pass through.
      return message;
    }
    UUID pinned = (UUID) sessionAttributes.get(SESSION_PINNED_PLAYER_ID_ATTR);
    if (pinned == null) {
      sessionAttributes.put(SESSION_PINNED_PLAYER_ID_ATTR, claimed);
      return message;
    }
    if (!pinned.equals(claimed)) {
      log.debug("STOMP frame rejected: claimed playerId does not match the session's pinned id");
      return rejectWithError(accessor, "playerId does not match the session's pinned id");
    }
    return message;
  }

  /**
   * Sends a STOMP ERROR frame back to the client via the {@code clientOutboundChannel} so the test
   * client (and a real client) can observe the rejection via {@code
   * StompSessionHandler.handleException} or the framework's frame dispatcher. Returning {@code
   * null} from {@link #preSend(Message, MessageChannel)} drops the message before it reaches the
   * broker — the combination gives "session stays open, message rejected" semantics.
   *
   * <p>The outbound channel is injected lazily ({@code @Lazy}) because the bean Spring publishes
   * for it is created by the same {@code @EnableWebSocketMessageBroker} infrastructure that
   * registers this interceptor; an eager dependency would close the bean-graph cycle and prevent
   * the context from coming up. {@code @Lazy} resolves the reference on first use, after the broker
   * initialization has completed.
   */
  private Message<?> rejectWithError(StompHeaderAccessor original, String reason) {
    StompHeaderAccessor errorAccessor = StompHeaderAccessor.create(StompCommand.ERROR);
    errorAccessor.setSessionId(original.getSessionId());
    errorAccessor.setMessage(reason);
    errorAccessor.setLeaveMutable(true);
    Message<byte[]> errorMessage =
        MessageBuilder.createMessage(new byte[0], errorAccessor.getMessageHeaders());
    try {
      clientOutboundChannel.send(errorMessage);
    } catch (RuntimeException ex) {
      log.warn("Failed to send STOMP ERROR frame: {}", ex.getMessage());
    }
    return null;
  }

  private static Optional<String> extractBearer(StompHeaderAccessor accessor) {
    List<String> headers = accessor.getNativeHeader("Authorization");
    if (headers == null || headers.isEmpty()) {
      return Optional.empty();
    }
    String header = headers.get(0);
    if (header == null || !header.startsWith(BEARER_PREFIX)) {
      return Optional.empty();
    }
    String token = header.substring(BEARER_PREFIX.length()).trim();
    return token.isEmpty() ? Optional.empty() : Optional.of(token);
  }

  /**
   * Attempts to verify the token, parse the {@code sub} claim as a UUID, and load the user. Returns
   * {@link Optional#empty()} on any failure — every JWT failure mode collapses to the same "stay
   * anonymous" outcome at this layer, the same uniform-failure posture {@code
   * JwtAuthenticationFilter} applies on the REST side.
   */
  private Optional<User> tryResolveUser(String token) {
    Claims claims;
    try {
      claims = jwtVerifier.verify(token);
    } catch (JwtException ex) {
      return Optional.empty();
    }
    UUID userId;
    try {
      userId = UUID.fromString(claims.getSubject());
    } catch (IllegalArgumentException | NullPointerException ex) {
      return Optional.empty();
    }
    return users.findById(userId);
  }

  /**
   * Extracts the {@code gameId} from a destination matching {@link #GAME_DESTINATION_PATTERN}.
   * Returns {@link Optional#empty()} when the destination is null, does not match the pattern, or
   * the captured segment is not a UUID.
   */
  private static Optional<UUID> extractGameIdFromDestination(String destination) {
    if (destination == null) {
      return Optional.empty();
    }
    Matcher matcher = GAME_DESTINATION_PATTERN.matcher(destination);
    if (!matcher.matches()) {
      return Optional.empty();
    }
    UUID gameId = parseUuidOrNull(matcher.group(1));
    return Optional.ofNullable(gameId);
  }

  /**
   * Looks up the game by id, returning {@code null} if the game does not exist. Mirrors the
   * tolerant lookup pattern {@link PlayerSessionTracker} uses at the STOMP boundary. Reads the
   * lower-level {@link GameStore} directly rather than going through {@code GameService}, which
   * would introduce a bean-creation cycle: {@code GameService} depends on the {@code
   * SimpMessagingTemplate} which is contributed by the {@code WebSocketConfig} that registers this
   * interceptor.
   */
  private Game findGameOrNull(UUID gameId) {
    return gameStore.findById(gameId).orElse(null);
  }

  /**
   * Returns the {@link Player} entry of the game (white or black) whose id equals {@code claimed},
   * or {@code null} if neither player matches. Used so the authenticated-session check can compare
   * {@link Player#userId()} against the session's authenticated user id.
   */
  private static Player matchPlayerOrNull(Game game, UUID claimed) {
    if (claimed.equals(game.white().id())) {
      return game.white();
    }
    if (claimed.equals(game.black().id())) {
      return game.black();
    }
    return null;
  }

  /**
   * Parses {@code value} as a {@link UUID}, returning {@code null} on malformed input. Same
   * tolerant pattern {@link PlayerSessionTracker} and {@link ViewerCountTracker} use at the STOMP
   * boundary.
   */
  private static UUID parseUuidOrNull(String value) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  /**
   * Lightweight {@link Principal} carrying both the {@link User#getId() user id} and the {@link
   * User} instance for downstream consumers. {@link #getName()} returns the user id as a string so
   * Spring's {@code @MessageMapping} principal-name resolution works without further wiring.
   */
  public record StompPrincipal(UUID userId, User user) implements Principal {
    @Override
    public String getName() {
      return userId.toString();
    }
  }
}
