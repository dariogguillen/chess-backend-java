package io.github.dariogguillen.chess.service.bot;

import io.github.dariogguillen.chess.domain.Move;

/**
 * Port (hexagonal-architecture seam) for a chess engine that, given a position, chooses a move
 * (feature 23, {@code bot-opponent}).
 *
 * <p>The single production adapter is {@link FairyStockfishBotEngine}, which talks the UCI protocol
 * to a Fairy-Stockfish subprocess. Keeping the engine behind this interface is what lets {@code
 * ./init.sh} stay green <em>without</em> the engine binary installed: integration tests register a
 * deterministic test double (or a Mockito stub) as the {@code BotEngine} bean and script the moves
 * they need, while only the gated {@code FairyStockfishEngineIT} exercises the real subprocess (and
 * skips when the binary is absent).
 *
 * <p>The contract is intentionally minimal and stateless: the FEN fully describes the position, so
 * an adapter needs no per-game handle and can spawn a fresh process per move.
 *
 * <p><strong>Strength model (feature 23.7, {@code bot-strength-fairy-stockfish}).</strong> {@link
 * #chooseMove} takes a target Elo. Feature 23.5 capped strength with official Stockfish's {@code
 * UCI_Elo}, which floors at ~1320; feature 23.7 replaces that with Lichess's model —
 * Fairy-Stockfish driven by {@code Skill Level} (extended {@code -20..20} range, negatives reaching
 * sub-1320) plus a capped search {@code depth}. The Elo→{@code (skill, depth)} translation lives
 * entirely inside the adapter via {@link BotEloMapping}, so this signature is unchanged. The
 * supported window {@link #MIN_BOT_ELO}–{@link #MAX_BOT_ELO} is the widened range the new model
 * reaches. These two constants are the single source of truth shared by {@code CreateRoomRequest}'s
 * {@code @Min}/{@code @Max} bounds and {@code BotProperties.defaultElo}'s validation, so the
 * request-level bound and the configured default can never drift.
 */
public interface BotEngine {

  /**
   * Lowest target Elo the bot accepts. Lowered from the old ~1320 {@code UCI_Elo} floor to ~400
   * once the engine switched to Fairy-Stockfish's {@code Skill Level} model (feature 23.7), which
   * can play like a beginner. The weakest tier ({@code Skill Level -9 / depth 5}) corresponds to
   * roughly this Elo in Lichess's mapping.
   */
  int MIN_BOT_ELO = 400;

  /**
   * Highest target Elo the bot accepts — full-strength play ({@code Skill Level 20 / depth 22}).
   */
  int MAX_BOT_ELO = 3190;

  /**
   * Chooses a move for the side to move in the position described by {@code fen}, playing at the
   * target strength {@code elo}.
   *
   * @param fen the current position in Forsyth-Edwards Notation; the side to move is encoded in the
   *     FEN itself.
   * @param elo the target strength. Expected within {@link #MIN_BOT_ELO}–{@link #MAX_BOT_ELO}; the
   *     production adapter maps it to a Fairy-Stockfish {@code (Skill Level, depth)} pair via
   *     {@link BotEloMapping}, which clamps out-of-range values to the weakest tier / full
   *     strength. The deterministic test double ignores it.
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
