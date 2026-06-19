package io.github.dariogguillen.chess.service.bot;

/**
 * The pair of Fairy-Stockfish search settings a target Elo maps to (feature 23.7, {@code
 * bot-strength-fairy-stockfish}): the {@code Skill Level} option value and the search {@code
 * depth}.
 *
 * <p>This is the output of {@link BotEloMapping#forElo(int)} and the only thing {@link
 * FairyStockfishBotEngine} needs to translate a requested Elo into the two UCI knobs Lichess uses
 * to weaken the engine.
 *
 * @param skillLevel the value for {@code setoption name Skill Level value <s>}; Fairy-Stockfish
 *     accepts the extended range {@code -20..20} (negatives are what reach sub-1320 play). Always
 *     within that range.
 * @param depth the value for {@code go depth <d>}; a small positive integer (5..22 across the
 *     Lichess tiers).
 */
public record EngineStrength(int skillLevel, int depth) {}
