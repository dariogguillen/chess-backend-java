package io.github.dariogguillen.chess.config;

import io.github.dariogguillen.chess.service.bot.BotEngine;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed binding for the {@code chess.bot.*} configuration namespace (feature 23, {@code
 * bot-opponent}). Mirrors the {@code CorsProperties} / {@code DisconnectProperties} pattern: a
 * record with a compact constructor that validates at bean-binding time so a misconfiguration fails
 * the boot fast rather than surfacing as a mid-game subprocess error.
 *
 * @param enginePath the absolute path to the Stockfish binary the {@code StockfishBotEngine}
 *     spawns. The default — {@code /usr/games/stockfish} — is where the Ubuntu/Debian {@code
 *     stockfish} package the Dockerfile installs puts the binary on the Temurin Jammy runtime
 *     image. Override locally (e.g. {@code /opt/homebrew/bin/stockfish} on macOS, {@code
 *     /usr/bin/stockfish} on some distros) via {@code CHESS_BOT_ENGINE_PATH} to run the gated
 *     {@code StockfishEngineIT}.
 * @param moveTime how long the engine searches per move ({@code go movetime <ms>}). The single-
 *     difficulty MVP knob: ~500ms is strong enough to be interesting yet answers in well under a
 *     second so the bot never flags on a timed game. Must be strictly positive.
 * @param moveTimeout the hard wall-clock deadline for the whole spawn → search → {@code bestmove}
 *     round-trip. If the subprocess does not return a {@code bestmove} within this window it is
 *     force-killed and a {@link io.github.dariogguillen.chess.service.bot.BotEngineException} is
 *     raised. Must be strictly greater than {@code moveTime}, otherwise a normal search would be
 *     killed mid-flight on every move.
 * @param poolSize the size of the dedicated bot {@code ExecutorService} (see {@code BotConfig}).
 *     Bot thinking runs off the request thread and off the clock {@code TaskScheduler}; at
 *     portfolio scale a small fixed pool is plenty. Must be at least 1.
 * @param defaultElo the target Stockfish strength (UCI Elo) applied when a BOT room is created
 *     without an explicit {@code botElo} (feature 23.5, {@code bot-difficulty}). Validated within
 *     {@link BotEngine#MIN_BOT_ELO}–{@link BotEngine#MAX_BOT_ELO} — the same bounds {@code
 *     CreateRoomRequest.botElo} enforces via {@code @Min}/{@code @Max}, so the configured default
 *     and the request-level bound cannot drift.
 */
@ConfigurationProperties("chess.bot")
public record BotProperties(
    String enginePath, Duration moveTime, Duration moveTimeout, int poolSize, int defaultElo) {

  public BotProperties {
    if (enginePath == null || enginePath.isBlank()) {
      throw new IllegalStateException(
          "chess.bot.engine-path must be set to the Stockfish binary path, got: " + enginePath);
    }
    if (moveTime == null || moveTime.isZero() || moveTime.isNegative()) {
      throw new IllegalStateException(
          "chess.bot.move-time must be strictly positive, got: " + moveTime);
    }
    if (moveTimeout == null || moveTimeout.isZero() || moveTimeout.isNegative()) {
      throw new IllegalStateException(
          "chess.bot.move-timeout must be strictly positive, got: " + moveTimeout);
    }
    if (moveTimeout.compareTo(moveTime) <= 0) {
      throw new IllegalStateException(
          "chess.bot.move-timeout ("
              + moveTimeout
              + ") must be strictly greater than chess.bot.move-time ("
              + moveTime
              + ") so a normal search is not killed mid-flight");
    }
    if (poolSize < 1) {
      throw new IllegalStateException("chess.bot.pool-size must be at least 1, got: " + poolSize);
    }
    if (defaultElo < BotEngine.MIN_BOT_ELO || defaultElo > BotEngine.MAX_BOT_ELO) {
      throw new IllegalStateException(
          "chess.bot.default-elo must be within ["
              + BotEngine.MIN_BOT_ELO
              + ", "
              + BotEngine.MAX_BOT_ELO
              + "], got: "
              + defaultElo);
    }
  }
}
