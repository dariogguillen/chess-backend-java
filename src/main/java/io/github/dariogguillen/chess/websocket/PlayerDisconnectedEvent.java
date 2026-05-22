package io.github.dariogguillen.chess.websocket;

import io.github.dariogguillen.chess.domain.Side;
import java.time.Instant;
import java.util.UUID;

/**
 * Wire shape broadcast over STOMP to {@code /topic/games/{gameId}} the moment {@code
 * PlayerSessionTracker} observes a {@code SessionDisconnectEvent} for a player whose game is still
 * non-terminal — i.e. the start of the grace window. Mid-grace UX layer added by feature 11.5
 * ({@code disconnect-notifications}); feature 11 ({@code disconnect-handling}) shipped the
 * lifecycle correctness without this event.
 *
 * <p>One of the four variants of the {@link GameStateEvent} sealed family on {@code
 * /topic/games/{gameId}}; subscribers discriminate by the leading {@code type} field (constant
 * {@code "PLAYER_DISCONNECTED"} here, set by the convenience constructor).
 *
 * <p>Together with {@link PlayerReconnectedEvent}, this event lets the opponent's UI render a "Bob
 * is reconnecting, 45s remaining" banner. {@code gracePeriodEndsAt} is an absolute instant (not a
 * {@code secondsRemaining} delta) so the value never goes stale on the wire — the client computes
 * {@code remaining = gracePeriodEndsAt - now()} once per render tick. This is the same
 * deadline-in-wire pattern that Lichess and chess.com use for clock state, and it sidesteps the
 * "the int was already wrong by the time the client saw it" failure mode of a {@code int
 * secondsRemaining} field.
 *
 * <p>The event is <em>not</em> emitted when the game is already in a terminal status at the time of
 * the disconnect — the same guard that suppresses the {@code GracePeriodManager.startGracePeriod}
 * call also suppresses this broadcast. A player dropping after checkmate must not produce a ghost
 * "reconnecting" banner.
 *
 * @param type the discriminator constant {@code "PLAYER_DISCONNECTED"}; set by the convenience
 *     constructor.
 * @param gameId the id of the game whose player just disconnected; matches the {@code {gameId}}
 *     segment in the topic.
 * @param playerId the id of the player whose STOMP session dropped.
 * @param side {@link Side#WHITE} or {@link Side#BLACK}, derived server-side from the game so the
 *     frontend can flag the right player slot without doing its own {@code
 *     game.white().id().equals(playerId)} check.
 * @param disconnectedAt the instant the disconnect handler observed the session drop, sourced from
 *     the injected {@code Clock}; in UTC.
 * @param gracePeriodEndsAt the absolute instant at which the {@link GameAbandonedEvent} will fire
 *     if the player has not reconnected — computed as {@code disconnectedAt +
 *     chess.disconnect.grace-period}; in UTC.
 */
public record PlayerDisconnectedEvent(
    String type,
    UUID gameId,
    UUID playerId,
    Side side,
    Instant disconnectedAt,
    Instant gracePeriodEndsAt)
    implements GameStateEvent {

  /** Stable discriminator value emitted in JSON as {@code "type":"PLAYER_DISCONNECTED"}. */
  public static final String TYPE = "PLAYER_DISCONNECTED";

  /**
   * Convenience constructor — the only call site producing this event. The {@link #type} field is
   * always {@link #TYPE}; making it explicit in the record — rather than computing it via
   * {@code @JsonTypeInfo} — keeps the discriminator visible at the source.
   */
  public PlayerDisconnectedEvent(
      UUID gameId, UUID playerId, Side side, Instant disconnectedAt, Instant gracePeriodEndsAt) {
    this(TYPE, gameId, playerId, side, disconnectedAt, gracePeriodEndsAt);
  }
}
