package io.github.dariogguillen.chess.service;

import io.github.dariogguillen.chess.domain.Game;
import io.github.dariogguillen.chess.domain.GameResult;
import io.github.dariogguillen.chess.domain.GameStatus;
import io.github.dariogguillen.chess.domain.Side;
import io.github.dariogguillen.chess.websocket.GameTimedOutEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Use-case orchestration for the "flag a timed game on the clock" lifecycle path added in feature
 * 22 ({@code time-control}). The spiritual twin of {@link GameAbandonService}: where the latter is
 * the terminal-by-grace-timeout path, this is the terminal-by-clock-flag path. The single public
 * operation, {@link #timeout(UUID)}, flips a non-terminal timed game to {@link GameStatus#TIMEOUT},
 * archives the resulting state to Postgres, and broadcasts a {@link GameTimedOutEvent} on the
 * existing {@code /topic/games/{gameId}} topic so both players' UIs can transition to "game over —
 * lost on time".
 *
 * <p>This component is the sole caller scheduled by {@code ClockTimerManager} when a per-game flag
 * timer fires. It is intentionally idempotent: if the game is already terminal (e.g. the opponent
 * delivered checkmate just before the flag, or a grace timer abandoned it first), the method
 * short-circuits with no archive and no broadcast — the same {@code gameStore.compute} + {@code
 * isTerminal()} guard {@code GameAbandonService} uses. Grace ({@code ABANDONED}) and clock ({@code
 * TIMEOUT}) race; whichever terminal mutation enters the {@code compute} block first wins, the
 * other observes the terminal status and is a no-op.
 *
 * <p>The flagged side is determined from the side-to-move at fire time: in a half-move count that
 * is even it is white's turn (white flags), odd it is black's turn (black flags). The flagged
 * side's remaining time is clamped to 0 in the broadcast.
 *
 * <p>Each successful {@link GameTimedOutEvent} broadcast emits a single INFO log line carrying the
 * destination and the key payload identifiers ({@code timedOutSide}, {@code winnerId}); the failure
 * path stays on WARN — mirroring feature 11.8's broadcast-observability discipline.
 */
@Component
public class GameTimeoutService {

  private static final Logger log = LoggerFactory.getLogger(GameTimeoutService.class);

  private final GameStore gameStore;
  private final GameHistoryService gameHistoryService;
  private final SimpMessagingTemplate messagingTemplate;
  private final Clock clock;

  public GameTimeoutService(
      GameStore gameStore,
      GameHistoryService gameHistoryService,
      SimpMessagingTemplate messagingTemplate,
      Clock clock) {
    this.gameStore = gameStore;
    this.gameHistoryService = gameHistoryService;
    this.messagingTemplate = messagingTemplate;
    this.clock = clock;
  }

  /**
   * Flags the game identified by {@code gameId} as {@link GameStatus#TIMEOUT}, archives it to
   * Postgres, and broadcasts a {@link GameTimedOutEvent} on {@code /topic/games/{gameId}}.
   *
   * <p>Idempotency: a no-op when the game does not exist, is untimed, or is already in a terminal
   * status. The status check runs inside the {@code gameStore.compute} block, atomic per {@code
   * gameId}, so a race against a concurrent terminal-by-move or terminal-by-grace write cannot
   * produce a double archive — whichever mutation enters the block first wins, the second observes
   * the terminal status and returns the existing value unchanged. The untimed guard is defensive:
   * {@code ClockTimerManager} only schedules flags for timed games, so reaching this method with an
   * untimed game would be a programming error.
   *
   * <p>The archive and broadcast run <em>outside</em> the {@code compute} block, for the same
   * reasoning {@code GameAbandonService.abandon} documents: terminal status is irrevocable, so
   * there is no race against further moves once {@code TIMEOUT} is committed, and pulling the JDBC
   * round-trip out of the Redis lock shrinks the critical section. The broadcast is wrapped in a
   * {@code try/catch + WARN log} that mirrors the fire-and-forget policy of {@code
   * GameService.broadcastMoveEvent}.
   *
   * @param gameId the game to flag; non-null.
   */
  public void timeout(UUID gameId) {
    AtomicBoolean transitioned = new AtomicBoolean(false);
    Game updated =
        gameStore.compute(
            gameId,
            (id, existing) -> {
              if (existing == null) {
                return null;
              }
              if (!existing.isTimed()) {
                return existing;
              }
              if (existing.status().isTerminal()) {
                return existing;
              }
              transitioned.set(true);
              // The side to move when the flag fired is the side that flagged (the loser). Stamp
              // the result onto the active game inside the compute block so the Redis copy and the
              // archived row agree on who won — the same derivation drives the broadcast winnerId.
              boolean whiteFlagged = existing.moves().size() % 2 == 0;
              GameResult result = GameResult.fromLoserToMove(whiteFlagged);
              return existing.withStatus(GameStatus.TIMEOUT).withResult(result);
            });
    if (updated == null || !transitioned.get()) {
      return;
    }
    gameHistoryService.archive(updated);

    // The side to move when the flag fired is the side that ran out of time. An even half-move
    // count means it is white's turn (white flagged); odd means black's.
    boolean whiteToMove = updated.moves().size() % 2 == 0;
    Side timedOutSide = whiteToMove ? Side.WHITE : Side.BLACK;
    UUID winnerId = whiteToMove ? updated.black().id() : updated.white().id();
    // The flagged side's remaining time is reported as 0; the opponent keeps the value frozen in
    // the active state at the last move.
    long whiteMs = whiteToMove ? 0L : updated.whiteTimeRemainingMs();
    long blackMs = whiteToMove ? updated.blackTimeRemainingMs() : 0L;

    GameTimedOutEvent event =
        new GameTimedOutEvent(
            gameId, timedOutSide, winnerId, updated.fen(), whiteMs, blackMs, Instant.now(clock));
    log.info(
        "Game timed out on clock: gameId={}, timedOutSide={}, winnerId={}",
        gameId,
        timedOutSide,
        winnerId);
    try {
      messagingTemplate.convertAndSend("/topic/games/" + gameId, event);
      log.info(
          "Broadcasted GameTimedOutEvent to {}: timedOutSide={}, winnerId={}",
          "/topic/games/" + gameId,
          timedOutSide,
          winnerId);
    } catch (RuntimeException ex) {
      log.warn("Failed to broadcast GameTimedOutEvent for game {}: {}", gameId, ex.getMessage());
    }
  }
}
