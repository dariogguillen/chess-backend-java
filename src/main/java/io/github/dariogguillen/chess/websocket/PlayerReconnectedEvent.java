package io.github.dariogguillen.chess.websocket;

import io.github.dariogguillen.chess.domain.Side;
import java.time.Instant;
import java.util.UUID;

/**
 * Wire shape broadcast over STOMP to {@code /topic/games/{gameId}} when {@code
 * PlayerSessionTracker} observes a {@code SessionSubscribeEvent} that cancels a pending grace timer
 * — i.e. a player reconnected within the grace window. The opponent's UI listens for this event to
 * clear the "reconnecting" banner opened by {@link PlayerDisconnectedEvent}.
 *
 * <p>One of the four variants of the {@link GameStateEvent} sealed family on {@code
 * /topic/games/{gameId}}; subscribers discriminate by the leading {@code type} field (constant
 * {@code "PLAYER_RECONNECTED"} here, set by the convenience constructor).
 *
 * <p>The broadcast is guarded by the boolean return of {@code
 * GracePeriodManager.cancelGracePeriod}: emitted only when the cancel actually removed a pending
 * timer. A fresh subscribe with no prior disconnect, or a reconnect that arrives after the timer
 * has already fired, both observe {@code false} and produce no broadcast. This keeps the wire
 * silent in the "timer-just-fired" race instead of broadcasting a {@code PLAYER_RECONNECTED}
 * concurrently with the {@code GAME_ABANDONED} that ended the game.
 *
 * @param type the discriminator constant {@code "PLAYER_RECONNECTED"}; set by the convenience
 *     constructor.
 * @param gameId the id of the game whose player just reconnected; matches the {@code {gameId}}
 *     segment in the topic.
 * @param playerId the id of the player whose STOMP session was restored.
 * @param side {@link Side#WHITE} or {@link Side#BLACK}, derived server-side from the game so the
 *     frontend can clear the right player slot's banner without doing its own membership check.
 * @param reconnectedAt the instant the subscribe handler observed the reconnect (and cancelled the
 *     pending timer), sourced from the injected {@code Clock}; in UTC.
 */
public record PlayerReconnectedEvent(
    String type, UUID gameId, UUID playerId, Side side, Instant reconnectedAt)
    implements GameStateEvent {

  /** Stable discriminator value emitted in JSON as {@code "type":"PLAYER_RECONNECTED"}. */
  public static final String TYPE = "PLAYER_RECONNECTED";

  /**
   * Convenience constructor — the only call site producing this event. The {@link #type} field is
   * always {@link #TYPE}; making it explicit in the record (rather than computing it via
   * {@code @JsonTypeInfo}) keeps the discriminator visible at the source.
   */
  public PlayerReconnectedEvent(UUID gameId, UUID playerId, Side side, Instant reconnectedAt) {
    this(TYPE, gameId, playerId, side, reconnectedAt);
  }
}
