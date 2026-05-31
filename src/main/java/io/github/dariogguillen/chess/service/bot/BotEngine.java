package io.github.dariogguillen.chess.service.bot;

import io.github.dariogguillen.chess.domain.Move;

/**
 * Port (hexagonal-architecture seam) for a chess engine that, given a position, chooses a move
 * (feature 23, {@code bot-opponent}).
 *
 * <p>The single production adapter is {@link StockfishBotEngine}, which talks the UCI protocol to a
 * Stockfish subprocess. Keeping the engine behind this interface is what lets {@code ./init.sh}
 * stay green <em>without</em> the Stockfish binary installed: integration tests register a
 * deterministic test double (or a Mockito stub) as the {@code BotEngine} bean and script the moves
 * they need, while only the gated {@code StockfishEngineIT} exercises the real subprocess (and
 * skips when the binary is absent).
 *
 * <p>The contract is intentionally minimal and stateless: the FEN fully describes the position, so
 * an adapter needs no per-game handle and can spawn a fresh process per move.
 *
 * <p><strong>Strength range (feature 23.5, {@code bot-difficulty}).</strong> {@link #chooseMove}
 * takes a target Elo. The supported window {@link #MIN_BOT_ELO}–{@link #MAX_BOT_ELO} is Stockfish's
 * {@code UCI_Elo} range; the engine clamps internally if its own build is narrower, so any in-range
 * value is safe to pass. These two constants are the single source of truth shared by {@code
 * CreateRoomRequest}'s {@code @Min}/{@code @Max} bounds and {@code BotProperties.defaultElo}'s
 * validation, so the request-level bound and the configured default can never drift.
 */
public interface BotEngine {

  /**
   * Lowest target Elo the bot accepts — Stockfish's {@code UCI_Elo} floor. Below this, strength is
   * controlled by {@code Skill Level} rather than {@code UCI_Elo}, which is a documented follow-up
   * (see {@code docs/architecture.md} → bot section).
   */
  int MIN_BOT_ELO = 1320;

  /** Highest target Elo the bot accepts — Stockfish's {@code UCI_Elo} ceiling. */
  int MAX_BOT_ELO = 3190;

  /**
   * Chooses a move for the side to move in the position described by {@code fen}, playing at the
   * target strength {@code elo}.
   *
   * @param fen the current position in Forsyth-Edwards Notation; the side to move is encoded in the
   *     FEN itself.
   * @param elo the target Stockfish strength (UCI Elo). Expected within {@link #MIN_BOT_ELO}–{@link
   *     #MAX_BOT_ELO}; the production adapter clamps via the engine itself if it is outside the
   *     build's range. The deterministic test double ignores it.
   * @return the engine's chosen move as a domain {@link Move}; never null. The move is expected to
   *     be legal in the position, but {@code BotMoveService} still routes it through {@code
   *     GameService.applyMove}, which re-validates it via {@code ChessRules} — a defensive double
   *     check that turns a buggy engine into the engine-failure terminal path rather than a corrupt
   *     game.
   * @throws BotEngineException if the engine cannot produce a move (spawn failure, timeout, process
   *     death, or an unparseable response).
   */
  Move chooseMove(String fen, int elo);
}
