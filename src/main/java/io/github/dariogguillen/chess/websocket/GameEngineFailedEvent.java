package io.github.dariogguillen.chess.websocket;

import io.github.dariogguillen.chess.domain.GameStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Wire shape broadcast over STOMP to {@code /topic/games/{gameId}} when a vs-bot game is terminated
 * because the chess engine failed to produce a move (feature 23, {@code bot-opponent}) — a
 * subprocess timeout, process death, or an illegal / unparseable {@code bestmove}.
 *
 * <p>The game is flipped to {@link GameStatus#ABANDONED} (the status is <em>reused</em>, not a new
 * enum value, to avoid the {@code MyGameSummary} / {@code PlayerGameSummary} allowable-values
 * cascade a new {@link GameStatus} would force), the human is recorded as the winner, and the game
 * is archived — the same idempotent {@code GameStore.compute} + {@code isTerminal()} guard {@code
 * GameAbandonService} / {@code GameTimeoutService} use. This event is the discriminated signal that
 * lets a subscriber tell "the engine broke" apart from a human-disconnect {@link
 * GameAbandonedEvent}, even though both land on {@code ABANDONED}.
 *
 * <p>Fired by {@code BotMoveService} <em>after</em> the {@code GameStore.compute} block flips the
 * status and {@code GameHistoryService.archive} writes the archive row — the same ordering as
 * {@link GameAbandonedEvent}. As one variant of the {@link GameStateEvent} sealed family on {@code
 * /topic/games/{gameId}}, subscribers discriminate by the leading {@code type} field (constant
 * {@code "GAME_ENGINE_FAILED"}). The field is set by the convenience constructor so the call site
 * does not need to know about the discriminator.
 *
 * @param type the discriminator constant {@code "GAME_ENGINE_FAILED"}; set by the convenience
 *     constructor.
 * @param gameId the id of the game that was terminated; matches the {@code {gameId}} segment in the
 *     topic.
 * @param winnerId the human player's id — they win by forfeit when the engine fails. Derived
 *     server-side so the frontend does not need a second lookup.
 * @param reason a stable machine-readable reason code; today always {@link
 *     #REASON_BOT_ENGINE_FAILURE}.
 * @param finalFen the FEN frozen on the board at the moment of failure; the game is terminal at
 *     this point and the FEN does not change again.
 * @param failedAt the instant the failure was finalised, sourced from the service's injected {@link
 *     java.time.Clock} so tests can pin it deterministically; in UTC.
 */
public record GameEngineFailedEvent(
    String type, UUID gameId, UUID winnerId, String reason, String finalFen, Instant failedAt)
    implements GameStateEvent {

  /** Stable discriminator value emitted in JSON as {@code "type":"GAME_ENGINE_FAILED"}. */
  public static final String TYPE = "GAME_ENGINE_FAILED";

  /** The only reason code today: the bot engine could not produce a legal move. */
  public static final String REASON_BOT_ENGINE_FAILURE = "BOT_ENGINE_FAILURE";

  /**
   * Convenience constructor used by every current call site (today: {@code BotMoveService}). The
   * {@link #type} field is always {@link #TYPE}; making it explicit in the record (rather than
   * computing it via {@code @JsonTypeInfo}) keeps the discriminator visible at the source.
   */
  public GameEngineFailedEvent(
      UUID gameId, UUID winnerId, String reason, String finalFen, Instant failedAt) {
    this(TYPE, gameId, winnerId, reason, finalFen, failedAt);
  }
}
