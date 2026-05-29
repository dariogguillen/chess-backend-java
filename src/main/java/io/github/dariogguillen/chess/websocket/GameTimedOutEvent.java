package io.github.dariogguillen.chess.websocket;

import io.github.dariogguillen.chess.domain.GameStatus;
import io.github.dariogguillen.chess.domain.Side;
import java.time.Instant;
import java.util.UUID;

/**
 * Wire shape broadcast over STOMP to {@code /topic/games/{gameId}} when a timed game transitions to
 * {@link GameStatus#TIMEOUT} because the side to move ran out of time (feature 22, {@code
 * time-control}). The flag fires server-side even when the timed-out player is offline — the clock
 * is the canonical, server-authoritative source of truth.
 *
 * <p>The event is fired by {@code GameTimeoutService.timeout} <em>after</em> the {@code
 * GameStore.compute} block flips the game status and {@code GameHistoryService.archive} writes the
 * archive row — the same ordering as {@link GameAbandonedEvent}. As one of the variants of the
 * {@link GameStateEvent} sealed family that share {@code /topic/games/{gameId}}, subscribers
 * discriminate by the leading {@code type} field (constant {@code "GAME_TIMED_OUT"} here). The
 * field is set automatically by the convenience constructor so call sites do not need to know about
 * the discriminator.
 *
 * @param type the discriminator constant {@code "GAME_TIMED_OUT"}; set by the convenience
 *     constructor.
 * @param gameId the id of the game that timed out; matches the {@code {gameId}} segment in the
 *     topic.
 * @param timedOutSide the side that ran out of time (the loser).
 * @param winnerId the id of the opponent — derived server-side so the frontend does not need a
 *     second lookup.
 * @param finalFen the FEN at the moment of the flag (the position frozen on the board); the game is
 *     terminal at this point and the FEN does not change again.
 * @param whiteTimeRemainingMs white's remaining clock at the flag, in milliseconds (the timed-out
 *     side reads 0).
 * @param blackTimeRemainingMs black's remaining clock at the flag, in milliseconds (the timed-out
 *     side reads 0).
 * @param timedOutAt the instant the flag was finalised, sourced from the service's injected {@link
 *     java.time.Clock} so tests can pin it deterministically; in UTC.
 */
public record GameTimedOutEvent(
    String type,
    UUID gameId,
    Side timedOutSide,
    UUID winnerId,
    String finalFen,
    long whiteTimeRemainingMs,
    long blackTimeRemainingMs,
    Instant timedOutAt)
    implements GameStateEvent {

  /** Stable discriminator value emitted in JSON as {@code "type":"GAME_TIMED_OUT"}. */
  public static final String TYPE = "GAME_TIMED_OUT";

  /**
   * Convenience constructor used by every current call site (today: {@code
   * GameTimeoutService.timeout}). The {@link #type} field is always {@link #TYPE}; making it
   * explicit in the record (rather than computing it via {@code @JsonTypeInfo}) keeps the
   * discriminator visible at the source.
   */
  public GameTimedOutEvent(
      UUID gameId,
      Side timedOutSide,
      UUID winnerId,
      String finalFen,
      long whiteTimeRemainingMs,
      long blackTimeRemainingMs,
      Instant timedOutAt) {
    this(
        TYPE,
        gameId,
        timedOutSide,
        winnerId,
        finalFen,
        whiteTimeRemainingMs,
        blackTimeRemainingMs,
        timedOutAt);
  }
}
