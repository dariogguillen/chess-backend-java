package io.github.dariogguillen.chess.service;

import io.github.dariogguillen.chess.domain.Game;
import io.github.dariogguillen.chess.domain.Move;
import io.github.dariogguillen.chess.domain.Player;
import io.github.dariogguillen.chess.domain.Side;
import io.github.dariogguillen.chess.exception.GameAlreadyEndedException;
import io.github.dariogguillen.chess.exception.GameNotFoundException;
import io.github.dariogguillen.chess.exception.IllegalMoveException;
import io.github.dariogguillen.chess.exception.NotYourTurnException;
import io.github.dariogguillen.chess.websocket.MoveEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 * Use-case orchestration for the chess-game lifecycle: read game state and apply a move on behalf
 * of one of the two players. The service is the entry point that the REST controller (and later the
 * WebSocket controller) will share; it holds no state of its own and delegates persistence to
 * {@link GameStore} and rule evaluation to {@link ChessRules}.
 *
 * <p>{@link #applyMove(UUID, UUID, Move)} runs the read-check-write block inside {@link
 * GameStore#compute(UUID, java.util.function.BiFunction)}, which serializes concurrent move
 * requests on the same {@code gameId}. The second of two racing callers either succeeds with the
 * next side's move (because the first one moved already and the turn has flipped) or receives a
 * {@link NotYourTurnException}; it never observes a half-state.
 *
 * <p>Each successful {@link MoveEvent} broadcast emits a single INFO log line carrying the
 * destination and the key payload identifiers ({@code movedBy}, {@code status}); the failure path
 * stays on WARN.
 */
@Service
public class GameService {

  private static final Logger log = LoggerFactory.getLogger(GameService.class);

  private final GameStore gameStore;
  private final ChessRules chessRules;
  private final SimpMessagingTemplate messagingTemplate;
  private final Clock clock;
  private final GameHistoryService gameHistoryService;
  private final ClockTimerManager clockTimerManager;

  public GameService(
      GameStore gameStore,
      ChessRules chessRules,
      SimpMessagingTemplate messagingTemplate,
      Clock clock,
      GameHistoryService gameHistoryService,
      ClockTimerManager clockTimerManager) {
    this.gameStore = gameStore;
    this.chessRules = chessRules;
    this.messagingTemplate = messagingTemplate;
    this.clock = clock;
    this.gameHistoryService = gameHistoryService;
    this.clockTimerManager = clockTimerManager;
  }

  /**
   * Reads the current game state by id.
   *
   * @param gameId the game identifier.
   * @return the game with the given id.
   * @throws GameNotFoundException if no game exists for {@code gameId}.
   */
  public Game findById(UUID gameId) {
    return gameStore.findById(gameId).orElseThrow(() -> new GameNotFoundException(gameId));
  }

  /**
   * Applies {@code move} on behalf of the player identified by {@code playerId}.
   *
   * <p>The read-check-write block runs inside {@link GameStore#compute(UUID,
   * java.util.function.BiFunction)}, atomic per {@code gameId}. The checks run in fixed order:
   * existence → not-already-ended → turn-belongs-to-caller → move-is-legal. Each failure throws a
   * specific exception that {@code GlobalExceptionHandler} maps to its documented HTTP status.
   *
   * <p><strong>Side effect on success:</strong> after {@code compute} returns with the updated
   * game, this method broadcasts a {@link MoveEvent} to {@code /topic/games/{gameId}} via {@link
   * SimpMessagingTemplate}. The broadcast happens <em>outside</em> the atomic block so that a
   * broker-side failure cannot look like a failed mutation. If the broadcast itself fails (broker
   * misconfigured, serialization throws, etc.), the failure is logged at {@code WARN} and
   * <em>not</em> rethrown: the REST POST has already mutated state successfully and the response
   * carries the new state. Subscribers may miss this update; recovery is a client concern
   * (reconnect + resync).
   *
   * @param gameId the game to mutate; non-null.
   * @param playerId the id of the caller (matches the {@code X-Player-Id} header at the HTTP
   *     boundary); non-null.
   * @param move the candidate move; non-null. Structural validity is enforced by {@link Move}'s
   *     compact constructor; chess legality is enforced here via {@link ChessRules}.
   * @return the updated {@link Game} after the move was applied; never null.
   * @throws GameNotFoundException if no game exists for {@code gameId}.
   * @throws GameAlreadyEndedException if the game is already in a terminal status.
   * @throws NotYourTurnException if it is not {@code playerId}'s turn to move.
   * @throws IllegalMoveException if chesslib rejects the move in the current position.
   */
  public Game applyMove(UUID gameId, UUID playerId, Move move) {
    // Mirrors the Game[1] holder idiom in RoomService.joinRoom: the lambda's return type is fixed
    // to Game, so we surface the produced value to the outer scope via a one-element array. The
    // alternative (re-reading the value after compute returns) would race against other writers.
    Game[] holder = new Game[1];
    gameStore.compute(
        gameId,
        (id, existing) -> {
          if (existing == null) {
            throw new GameNotFoundException(id);
          }
          if (existing.status().isTerminal()) {
            throw new GameAlreadyEndedException(id, existing.status());
          }
          boolean whiteToMove = existing.moves().size() % 2 == 0;
          Player expected = whiteToMove ? existing.white() : existing.black();
          if (!expected.id().equals(playerId)) {
            throw new NotYourTurnException(id, expected.id());
          }
          GameState state =
              new GameState(
                  existing.startingFen(), existing.moves(), existing.fen(), existing.status());
          MoveOutcome outcome = chessRules.applyMove(state, move);
          if (!outcome.legal()) {
            throw new IllegalMoveException(id, move);
          }
          // Clock advance (timed games only). The mover S just spent (now - lastMoveAt); decrement
          // S's remaining time, clamp at 0, then add the Fischer increment back. The clock anchor
          // (lastMoveAt) moves to now so the next side's elapsed counts from this instant. Untimed
          // games leave all four clock fields null — the all-or-nothing invariant holds.
          Long newWhiteMs = existing.whiteTimeRemainingMs();
          Long newBlackMs = existing.blackTimeRemainingMs();
          Instant newLastMoveAt = existing.lastMoveAt();
          if (existing.isTimed()) {
            Instant now = Instant.now(clock);
            long elapsed = now.toEpochMilli() - existing.lastMoveAt().toEpochMilli();
            long moverRemaining = whiteToMove ? newWhiteMs : newBlackMs;
            long afterDecrement = Math.max(0L, moverRemaining - elapsed);
            long afterIncrement = afterDecrement + existing.incrementMs();
            if (whiteToMove) {
              newWhiteMs = afterIncrement;
            } else {
              newBlackMs = afterIncrement;
            }
            newLastMoveAt = now;
          }
          Game updated =
              new Game(
                  existing.id(),
                  existing.roomId(),
                  existing.white(),
                  existing.black(),
                  existing.startingFen(),
                  outcome.state().currentFen(),
                  outcome.state().currentStatus(),
                  outcome.state().history(),
                  newWhiteMs,
                  newBlackMs,
                  newLastMoveAt,
                  existing.incrementMs());
          // Archive to Postgres BEFORE the GameStore.compute write completes (the store writes
          // the lambda's return value). If archive throws, the compute lambda throws, the move
          // request fails with 500, and Redis still holds the previous state — strictly better
          // than the inverse (Redis advances but the archive silently disappears, leaving a
          // ghost terminal game that nobody can ever observe in history).
          if (updated.status().isTerminal()) {
            gameHistoryService.archive(updated);
          }
          holder[0] = updated;
          return updated;
        });
    Game updated = holder[0];
    log.info(
        "Move applied: gameId={}, move={}, newStatus={}", updated.id(), move, updated.status());
    rescheduleFlagTimer(updated);
    broadcastMoveEvent(updated, playerId, move);
    return updated;
  }

  /**
   * Re-arms (or cancels) the per-game flag timer after a move, outside the {@code compute} block.
   * For an untimed game this is a no-op. For a timed game: if the move ended the game (terminal
   * status), the now-irrelevant flag is cancelled; otherwise the flag is rescheduled for the new
   * side-to-move at {@code lastMoveAt + remaining[newSide]}. The new side-to-move is whoever is to
   * move after the just-applied move — an even half-move count means white is to move, odd means
   * black.
   */
  private void rescheduleFlagTimer(Game updated) {
    if (!updated.isTimed()) {
      return;
    }
    if (updated.status().isTerminal()) {
      clockTimerManager.cancel(updated.id());
      return;
    }
    boolean whiteToMoveNext = updated.moves().size() % 2 == 0;
    long remaining =
        whiteToMoveNext ? updated.whiteTimeRemainingMs() : updated.blackTimeRemainingMs();
    Instant deadline = updated.lastMoveAt().plusMillis(remaining);
    clockTimerManager.scheduleFlag(updated.id(), deadline);
  }

  /**
   * Builds and broadcasts a {@link MoveEvent} for the just-applied move. Runs <em>outside</em> the
   * {@code compute} lambda so that a broker-side failure cannot propagate out and look like a
   * failed mutation. Any {@link RuntimeException} thrown by the broker is caught and logged at
   * {@code WARN}; the broadcast is fire-and-forget by design.
   */
  private void broadcastMoveEvent(Game updated, UUID playerId, Move move) {
    int moveNumber = updated.moves().size();
    // After the move is appended, an odd moveNumber means White's move just landed (move 1 is
    // White's first), an even moveNumber means Black's. The next turn is therefore the inverse.
    Side side = (moveNumber % 2 == 1) ? Side.WHITE : Side.BLACK;
    Side turn = (side == Side.WHITE) ? Side.BLACK : Side.WHITE;
    String promotion = move.promotion().map(Enum::name).orElse(null);

    MoveEvent event =
        new MoveEvent(
            updated.id(),
            playerId,
            side,
            move.from().value(),
            move.to().value(),
            promotion,
            updated.fen(),
            updated.status(),
            turn,
            moveNumber,
            Instant.now(clock),
            updated.whiteTimeRemainingMs(),
            updated.blackTimeRemainingMs());

    try {
      messagingTemplate.convertAndSend("/topic/games/" + updated.id(), event);
      log.info(
          "Broadcasted MoveEvent to {}: movedBy={}, status={}",
          "/topic/games/" + updated.id(),
          playerId,
          updated.status());
    } catch (RuntimeException ex) {
      log.warn("Failed to broadcast MoveEvent for game {}: {}", updated.id(), ex.getMessage());
    }
  }
}
