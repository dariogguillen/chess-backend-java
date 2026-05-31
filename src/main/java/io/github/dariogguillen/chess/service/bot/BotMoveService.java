package io.github.dariogguillen.chess.service.bot;

import io.github.dariogguillen.chess.config.BotConfig;
import io.github.dariogguillen.chess.config.BotProperties;
import io.github.dariogguillen.chess.domain.Game;
import io.github.dariogguillen.chess.domain.GameStatus;
import io.github.dariogguillen.chess.domain.Move;
import io.github.dariogguillen.chess.domain.Player;
import io.github.dariogguillen.chess.service.GameHistoryService;
import io.github.dariogguillen.chess.service.GameService;
import io.github.dariogguillen.chess.service.GameStore;
import io.github.dariogguillen.chess.websocket.GameEngineFailedEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the bot's turn in a vs-bot game (feature 23, {@code bot-opponent}).
 *
 * <p><strong>No dependency cycle.</strong> This service depends on {@link GameService} (to reuse
 * its {@code applyMove} pipeline verbatim — validation, clock advance, archive, {@code MoveEvent}
 * broadcast), on {@link GameStore} (to read the authoritative current game), and on {@link
 * BotEngine} (the port). Nothing depends back on it: the two call sites — {@code GameController}
 * after a human move, and {@code RoomController} after a bot-as-white game is created — invoke it,
 * but it never invokes them.
 *
 * <p><strong>Off-request async.</strong> {@link #maybeTriggerBotMove(Game)} returns immediately;
 * the actual think-and-move runs on the dedicated bot {@link ExecutorService} ({@code BotConfig}),
 * never on the request thread and never on the clock {@code TaskScheduler}. A human's {@code POST
 * /moves} therefore returns with their own move applied; the bot's move arrives moments later as an
 * ordinary {@code MoveEvent} on the same topic.
 *
 * <p><strong>Engine-failure terminal path.</strong> If the engine throws (timeout, process death)
 * or its move is rejected by {@code applyMove} (illegal / unparseable bestmove), the task
 * terminates the game on the {@link #failGame(UUID)} path: status {@link GameStatus#ABANDONED}
 * (reused — not a new status), the human as winner, archived via the same idempotent {@code
 * GameStore.compute} + {@code isTerminal()} guard {@code GameAbandonService} uses, and a {@link
 * GameEngineFailedEvent} broadcast. The subprocess is closed by the engine adapter's own {@code
 * finally}; the executor task never leaks a thread because it catches everything.
 */
@Service
public class BotMoveService {

  private static final Logger log = LoggerFactory.getLogger(BotMoveService.class);

  private final GameStore gameStore;
  private final GameService gameService;
  private final GameHistoryService gameHistoryService;
  private final BotEngine botEngine;
  private final BotProperties botProperties;
  private final ExecutorService botExecutor;
  private final SimpMessagingTemplate messagingTemplate;
  private final Clock clock;

  public BotMoveService(
      GameStore gameStore,
      GameService gameService,
      GameHistoryService gameHistoryService,
      BotEngine botEngine,
      BotProperties botProperties,
      @Qualifier(BotConfig.BOT_EXECUTOR) ExecutorService botExecutor,
      SimpMessagingTemplate messagingTemplate,
      Clock clock) {
    this.gameStore = gameStore;
    this.gameService = gameService;
    this.gameHistoryService = gameHistoryService;
    this.botEngine = botEngine;
    this.botProperties = botProperties;
    this.botExecutor = botExecutor;
    this.messagingTemplate = messagingTemplate;
    this.clock = clock;
  }

  /**
   * Submits a bot move for {@code game} when, and only when, it is the bot's turn. A no-op (returns
   * without scheduling) when the game is not vs-bot, is already terminal, or the side to move is
   * the human. Safe to call after every human move and after bot-as-white game creation — the guard
   * keeps it idempotent against being called on a human-to-move position.
   *
   * <p>The decision is re-checked on the executor thread against the freshly read game (the {@code
   * game} argument may be a moment stale by the time the task runs), so a race against a concurrent
   * human move cannot make the bot move twice or move out of turn.
   *
   * @param game the current game snapshot; non-null.
   */
  public void maybeTriggerBotMove(Game game) {
    if (!isBotToMove(game)) {
      return;
    }
    UUID gameId = game.id();
    botExecutor.submit(() -> runBotMove(gameId));
  }

  /** The body of the async task: read the authoritative game, think, and apply the move. */
  private void runBotMove(UUID gameId) {
    Game current = gameStore.findById(gameId).orElse(null);
    if (current == null || !isBotToMove(current)) {
      // The game vanished (archived) or a concurrent mutation flipped the turn / terminated it
      // between submit and execution. Nothing to do.
      return;
    }
    try {
      // The target strength rides on the Game (feature 23.5). A bot game created without an
      // explicit botElo — or one deserialised from a pre-feature-23.5 Redis snapshot (null field) —
      // falls back to the configured default so it still plays.
      int elo = current.botElo() != null ? current.botElo() : botProperties.defaultElo();
      Move move = botEngine.chooseMove(current.fen(), elo);
      // Reuse the human pipeline verbatim: validation, clock advance, archive-on-terminal, and the
      // MoveEvent broadcast all happen inside applyMove. The bot's sentinel id passes the turn
      // check because it is the side-to-move's player id.
      gameService.applyMove(gameId, Player.BOT_PLAYER_ID, move);
    } catch (RuntimeException ex) {
      // Engine timeout / process death (BotEngineException) OR an illegal/unparseable bestmove that
      // applyMove rejected (IllegalMoveException, NotYourTurnException, etc.). Either way the bot
      // cannot continue the game; terminate it on the engine-failure path. We deliberately catch
      // broadly so no failure escapes onto the executor thread (which would silently die).
      log.error("Bot engine failed for game {}: {}", gameId, ex.getMessage(), ex);
      failGame(gameId);
    }
  }

  /**
   * Whether {@code game} is a vs-bot game whose side to move is the bot and is still in progress.
   * The side to move follows the same half-move-parity rule the rest of the codebase uses: an even
   * move count means white is to move.
   */
  private boolean isBotToMove(Game game) {
    if (game.status().isTerminal()) {
      return false;
    }
    if (!game.white().isBot() && !game.black().isBot()) {
      return false;
    }
    boolean whiteToMove = game.moves().size() % 2 == 0;
    Player sideToMove = whiteToMove ? game.white() : game.black();
    return sideToMove.isBot();
  }

  /**
   * Terminates the game on the engine-failure path: flip to {@link GameStatus#ABANDONED}, archive,
   * and broadcast a {@link GameEngineFailedEvent} with the human as winner. Idempotent — a no-op if
   * the game is gone or already terminal — using the same {@code compute} + {@code isTerminal()}
   * guard as {@code GameAbandonService}, so a race against a concurrent terminal-by-move write
   * cannot double-archive.
   */
  private void failGame(UUID gameId) {
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
    // The human is whichever side is not the bot; they win by forfeit.
    UUID winnerId = updated.white().isBot() ? updated.black().id() : updated.white().id();
    GameEngineFailedEvent event =
        new GameEngineFailedEvent(
            gameId,
            winnerId,
            GameEngineFailedEvent.REASON_BOT_ENGINE_FAILURE,
            updated.fen(),
            Instant.now(clock));
    log.warn("Game terminated by bot engine failure: gameId={}, winnerId={}", gameId, winnerId);
    try {
      messagingTemplate.convertAndSend("/topic/games/" + gameId, event);
      log.info(
          "Broadcasted GameEngineFailedEvent to {}: winnerId={}, reason={}",
          "/topic/games/" + gameId,
          winnerId,
          GameEngineFailedEvent.REASON_BOT_ENGINE_FAILURE);
    } catch (RuntimeException ex) {
      log.warn(
          "Failed to broadcast GameEngineFailedEvent for game {}: {}", gameId, ex.getMessage());
    }
  }
}
