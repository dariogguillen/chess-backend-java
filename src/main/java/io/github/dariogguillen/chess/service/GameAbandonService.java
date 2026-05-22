package io.github.dariogguillen.chess.service;

import io.github.dariogguillen.chess.domain.Game;
import io.github.dariogguillen.chess.domain.GameStatus;
import io.github.dariogguillen.chess.websocket.GameAbandonedEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Use-case orchestration for the "abandon a game" lifecycle path added in feature 11 ({@code
 * disconnect-handling}). The single public operation, {@link #abandon(UUID, UUID)}, is the
 * terminal-by-timeout counterpart of {@code GameService.applyMove}'s terminal-by-move path: it
 * flips a non-terminal game to {@link GameStatus#ABANDONED}, archives the resulting state to
 * Postgres, and broadcasts a {@link GameAbandonedEvent} on the existing {@code
 * /topic/games/{gameId}} topic so the opponent's UI can transition to "game over".
 *
 * <p>This component is the sole caller scheduled by {@code GracePeriodManager} when the per-player
 * grace timer fires. It is intentionally idempotent: if the game is already terminal (e.g. the
 * opponent delivered checkmate during the grace window, or a parallel disconnect of the other
 * player abandoned it first), the method short-circuits with no archive and no broadcast.
 *
 * <p>The "archive on terminal status" trigger now lives in <strong>two</strong> places — {@code
 * GameService.applyMove} for terminal-by-move and this method for terminal-by-timeout. We
 * deliberately did <em>not</em> extract a shared helper: the two call sites have different
 * surrounding context (move applied vs status mutated) and a shared helper would obscure that.
 * Documented in {@code notes/11-disconnect-handling.md}.
 */
@Component
public class GameAbandonService {

  private static final Logger log = LoggerFactory.getLogger(GameAbandonService.class);

  private final GameStore gameStore;
  private final GameHistoryService gameHistoryService;
  private final SimpMessagingTemplate messagingTemplate;
  private final Clock clock;

  public GameAbandonService(
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
   * Marks the game identified by {@code gameId} as {@link GameStatus#ABANDONED}, archives it to
   * Postgres, and broadcasts a {@link GameAbandonedEvent} on {@code /topic/games/{gameId}}.
   *
   * <p>Idempotency: a no-op when the game does not exist or is already in a terminal status. The
   * status check runs inside the {@code gameStore.compute} block, atomic per {@code gameId}, so a
   * race against a concurrent terminal-by-move write cannot produce a double archive — whichever
   * mutation enters the block first wins, the second observes the terminal status and returns the
   * existing value unchanged.
   *
   * <p>The archive runs <em>outside</em> the {@code compute} block (unlike the move path, which
   * runs it inside). The reasoning: the abandon path has no race against further moves on the same
   * game once {@code ABANDONED} is committed — terminal status is irrevocable — so there is no
   * upside to keeping the Postgres write inside the Redis lock. Pulling it out shrinks the critical
   * section by the duration of a JDBC round-trip. The broadcast runs after the archive for the same
   * reason and is wrapped in a {@code try/catch + WARN log} that mirrors the fire-and-forget policy
   * used by {@code GameService.broadcastMoveEvent}.
   *
   * @param gameId the game to abandon; non-null.
   * @param abandonedBy the id of the player whose session dropped and was not restored within the
   *     grace period — i.e. the loser. Must match white or black of the game; if it does not, the
   *     winner derivation falls back to white (defensive; the {@code GracePeriodManager} only
   *     schedules with ids it pulled from the game itself, so this branch should be unreachable in
   *     production).
   */
  public void abandon(UUID gameId, UUID abandonedBy) {
    AtomicBoolean transitioned = new AtomicBoolean(false);
    Game updated =
        gameStore.compute(
            gameId,
            (id, existing) -> {
              if (existing == null) {
                return null;
              }
              if (existing.status().isTerminal()) {
                return existing;
              }
              transitioned.set(true);
              return existing.withStatus(GameStatus.ABANDONED);
            });
    if (updated == null || !transitioned.get()) {
      return;
    }
    gameHistoryService.archive(updated);
    UUID winnerId =
        updated.white().id().equals(abandonedBy) ? updated.black().id() : updated.white().id();
    GameAbandonedEvent event =
        new GameAbandonedEvent(gameId, abandonedBy, winnerId, updated.fen(), Instant.now(clock));
    log.info(
        "Game abandoned by timeout: gameId={}, abandonedBy={}, winnerId={}",
        gameId,
        abandonedBy,
        winnerId);
    try {
      messagingTemplate.convertAndSend("/topic/games/" + gameId, event);
    } catch (RuntimeException ex) {
      log.warn("Failed to broadcast GameAbandonedEvent for game {}: {}", gameId, ex.getMessage());
    }
  }
}
