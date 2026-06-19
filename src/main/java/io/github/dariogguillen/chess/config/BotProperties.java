package io.github.dariogguillen.chess.config;

import io.github.dariogguillen.chess.service.bot.BotEngine;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed binding for the {@code chess.bot.*} configuration namespace (feature 23, {@code
 * bot-opponent}; strength model reworked in feature 23.7, {@code bot-strength-fairy-stockfish}).
 * Mirrors the {@code CorsProperties} / {@code DisconnectProperties} pattern: a record with a
 * compact constructor that validates at bean-binding time so a misconfiguration fails the boot fast
 * rather than surfacing as a mid-game subprocess error.
 *
 * @param enginePath the absolute path to the Fairy-Stockfish binary the {@code
 *     FairyStockfishBotEngine} spawns. The default — {@code /usr/local/bin/fairy-stockfish} — is
 *     where the Dockerfile installs the pinned binary it downloads from Fairy-Stockfish's GitHub
 *     releases (Fairy-Stockfish is not packaged in apt). Override locally via {@code
 *     CHESS_BOT_ENGINE_PATH} to run the gated {@code FairyStockfishEngineIT} against a binary you
 *     have installed.
 * @param moveTimeout the hard wall-clock deadline for the whole spawn → search → {@code bestmove}
 *     round-trip. If the subprocess does not return a {@code bestmove} within this window it is
 *     force-killed and a {@link io.github.dariogguillen.chess.service.bot.BotEngineException} is
 *     raised. Since feature 23.7 the search is bounded by {@code go depth <d>} (not {@code
 *     movetime}); the default is sized so even a top-tier {@code go depth 22} with classical eval
 *     completes within it on a t3.micro. Must be strictly positive.
 * @param poolSize the size of the dedicated bot {@code ExecutorService} (see {@code BotConfig}).
 *     Bot thinking runs off the request thread and off the clock {@code TaskScheduler}; at
 *     portfolio scale a small fixed pool is plenty. Must be at least 1.
 * @param defaultElo the target bot strength applied when a BOT room is created without an explicit
 *     {@code botElo} (feature 23.5, {@code bot-difficulty}). Validated within {@link
 *     BotEngine#MIN_BOT_ELO}–{@link BotEngine#MAX_BOT_ELO} — the same bounds {@code
 *     CreateRoomRequest.botElo} enforces via {@code @Min}/{@code @Max}, so the configured default
 *     and the request-level bound cannot drift.
 */
@ConfigurationProperties("chess.bot")
public record BotProperties(String enginePath, Duration moveTimeout, int poolSize, int defaultElo) {

  public BotProperties {
    if (enginePath == null || enginePath.isBlank()) {
      throw new IllegalStateException(
          "chess.bot.engine-path must be set to the Fairy-Stockfish binary path, got: "
              + enginePath);
    }
    if (moveTimeout == null || moveTimeout.isZero() || moveTimeout.isNegative()) {
      throw new IllegalStateException(
          "chess.bot.move-timeout must be strictly positive, got: " + moveTimeout);
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
