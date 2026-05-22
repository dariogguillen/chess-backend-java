package io.github.dariogguillen.chess.websocket;

import io.github.dariogguillen.chess.domain.GameStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Wire shape broadcast over STOMP to {@code /topic/games/{gameId}} when a game transitions to
 * {@link GameStatus#ABANDONED} because one of the players lost their STOMP session and did not
 * resubscribe within the configured grace period.
 *
 * <p>The event is fired by {@code GameAbandonService.abandon} <em>after</em> the {@code
 * GameStore.compute} block flips the game status and {@code GameHistoryService.archive} writes the
 * archive row. As one of the four variants of the {@link GameStateEvent} sealed family that share
 * {@code /topic/games/{gameId}}, subscribers discriminate by the leading {@code type} field
 * (constant {@code "GAME_ABANDONED"} here). The field is set automatically by the convenience
 * constructor so existing call sites do not need to know about the discriminator.
 *
 * @param type the discriminator constant {@code "GAME_ABANDONED"}; set by the convenience
 *     constructor.
 * @param gameId the id of the game that was abandoned; matches the {@code {gameId}} segment in the
 *     topic.
 * @param abandonedBy the id of the player whose session dropped and was not restored before the
 *     grace period elapsed; this is the loser.
 * @param winnerId the id of the opponent — derived server-side so the frontend does not need a
 *     second lookup.
 * @param finalFen the FEN at the moment of abandonment (the position frozen on the board); the game
 *     is terminal at this point and the FEN does not change again.
 * @param abandonedAt the instant the abandonment was finalized, sourced from the service's injected
 *     {@link java.time.Clock} so tests can pin it deterministically; in UTC.
 */
public record GameAbandonedEvent(
    String type, UUID gameId, UUID abandonedBy, UUID winnerId, String finalFen, Instant abandonedAt)
    implements GameStateEvent {

  /** Stable discriminator value emitted in JSON as {@code "type":"GAME_ABANDONED"}. */
  public static final String TYPE = "GAME_ABANDONED";

  /**
   * Convenience constructor used by every current call site (today: {@code
   * GameAbandonService.abandon}). The {@link #type} field is always {@link #TYPE}; making it
   * explicit in the record (rather than computing it via {@code @JsonTypeInfo}) keeps the
   * discriminator visible at the source.
   */
  public GameAbandonedEvent(
      UUID gameId, UUID abandonedBy, UUID winnerId, String finalFen, Instant abandonedAt) {
    this(TYPE, gameId, abandonedBy, winnerId, finalFen, abandonedAt);
  }
}
