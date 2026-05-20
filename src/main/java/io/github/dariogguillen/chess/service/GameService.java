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
 * <p>{@link #applyMove(String, String, Move)} runs the read-check-write block inside {@link
 * GameStore#compute(String, java.util.function.BiFunction)}, which serializes concurrent move
 * requests on the same {@code gameId}. The second of two racing callers either succeeds with the
 * next side's move (because the first one moved already and the turn has flipped) or receives a
 * {@link NotYourTurnException}; it never observes a half-state.
 */
@Service
public class GameService {

  private static final Logger log = LoggerFactory.getLogger(GameService.class);

  private final GameStore gameStore;
  private final ChessRules chessRules;
  private final SimpMessagingTemplate messagingTemplate;
  private final Clock clock;

  public GameService(
      GameStore gameStore,
      ChessRules chessRules,
      SimpMessagingTemplate messagingTemplate,
      Clock clock) {
    this.gameStore = gameStore;
    this.chessRules = chessRules;
    this.messagingTemplate = messagingTemplate;
    this.clock = clock;
  }

  /**
   * Reads the current game state by id.
   *
   * @param gameId the game identifier.
   * @return the game with the given id.
   * @throws GameNotFoundException if no game exists for {@code gameId}.
   */
  public Game findById(String gameId) {
    return gameStore.findById(gameId).orElseThrow(() -> new GameNotFoundException(gameId));
  }

  /**
   * Applies {@code move} on behalf of the player identified by {@code playerId}.
   *
   * <p>The read-check-write block runs inside {@link GameStore#compute(String,
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
  public Game applyMove(String gameId, String playerId, Move move) {
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
          Game updated =
              new Game(
                  existing.id(),
                  existing.roomId(),
                  existing.white(),
                  existing.black(),
                  existing.startingFen(),
                  outcome.state().currentFen(),
                  outcome.state().currentStatus(),
                  outcome.state().history());
          holder[0] = updated;
          return updated;
        });
    Game updated = holder[0];
    log.info(
        "Move applied: gameId={}, move={}, newStatus={}", updated.id(), move, updated.status());
    broadcastMoveEvent(updated, playerId, move);
    return updated;
  }

  /**
   * Builds and broadcasts a {@link MoveEvent} for the just-applied move. Runs <em>outside</em> the
   * {@code compute} lambda so that a broker-side failure cannot propagate out and look like a
   * failed mutation. Any {@link RuntimeException} thrown by the broker is caught and logged at
   * {@code WARN}; the broadcast is fire-and-forget by design.
   */
  private void broadcastMoveEvent(Game updated, String playerId, Move move) {
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
            Instant.now(clock));

    try {
      messagingTemplate.convertAndSend("/topic/games/" + updated.id(), event);
    } catch (RuntimeException ex) {
      log.warn("Failed to broadcast MoveEvent for game {}: {}", updated.id(), ex.getMessage());
    }
  }
}
