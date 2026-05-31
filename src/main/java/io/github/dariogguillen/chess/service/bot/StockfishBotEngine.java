package io.github.dariogguillen.chess.service.bot;

import io.github.dariogguillen.chess.config.BotProperties;
import io.github.dariogguillen.chess.domain.Move;
import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Production {@link BotEngine} adapter that drives a Stockfish subprocess over the UCI protocol
 * (feature 23, {@code bot-opponent}).
 *
 * <p><strong>Spawn-per-move lifecycle.</strong> Each {@link #chooseMove(String, int)} call spawns a
 * fresh {@code stockfish} process via {@link ProcessBuilder}, runs one UCI handshake + search,
 * parses the {@code bestmove}, and tears the process down. The engine is stateless (the FEN fully
 * describes the position), so there is no per-game process to track and no shared mutable state —
 * robust on a memory-constrained t3.micro and trivially correct under concurrency (the dedicated
 * bot executor may run several searches at once, each in its own process).
 *
 * <p><strong>Guaranteed cleanup.</strong> The process is <em>always</em> torn down in a {@code
 * finally} block via {@link Process#destroyForcibly()} — even on a timeout, an I/O error, or an
 * unparseable response. Combined with the {@code waitFor(timeout)} hard deadline this guarantees no
 * orphaned {@code stockfish} process survives a failed search. This is the JVM idiom equivalent of
 * a Typelevel {@code Resource[F, Process]} with a guaranteed release.
 *
 * <p>The UCI exchange is the minimum that produces a move:
 *
 * <pre>
 *   &gt; uci
 *   &lt; ... uciok
 *   &gt; isready
 *   &gt; setoption name UCI_LimitStrength value true
 *   &gt; setoption name UCI_Elo value &lt;elo&gt;
 *   &gt; position fen &lt;fen&gt;
 *   &gt; go movetime &lt;ms&gt;
 *   &lt; ... bestmove e2e4 ponder ...
 *   &gt; quit
 * </pre>
 *
 * <p><strong>Strength limiting (feature 23.5, {@code bot-difficulty}).</strong> The two {@code
 * setoption} lines cap the engine to the target Elo. {@code UCI_LimitStrength=true} switches
 * Stockfish from "play the strongest move" to "play at the configured {@code UCI_Elo}"; the engine
 * clamps the value to its own build's range, so passing an in-range Elo (see {@link
 * BotEngine#MIN_BOT_ELO}–{@link BotEngine#MAX_BOT_ELO}) is always safe. The options are sent after
 * {@code isready} and before {@code position} / {@code go}, which is what UCI requires (options are
 * set on a ready engine, before the search).
 *
 * <p>Any deviation (spawn failure, the deadline elapsing before {@code bestmove}, a {@code bestmove
 * (none)} for a finished position, or a malformed line) is surfaced as a {@link
 * BotEngineException}.
 */
@Component
public class StockfishBotEngine implements BotEngine {

  private static final Logger log = LoggerFactory.getLogger(StockfishBotEngine.class);

  private final BotProperties properties;

  public StockfishBotEngine(BotProperties properties) {
    this.properties = properties;
  }

  @Override
  public Move chooseMove(String fen, int elo) {
    long deadlineMillis = properties.moveTimeout().toMillis();
    long moveTimeMillis = properties.moveTime().toMillis();

    Process process = spawn();
    try (BufferedWriter writer =
            new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        BufferedReader reader =
            new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {

      send(writer, "uci");
      send(writer, "isready");
      // Cap the engine to the target Elo before the search. UCI_LimitStrength flips Stockfish into
      // Elo-limited mode; UCI_Elo is the target, which the engine clamps to its build's range.
      send(writer, "setoption name UCI_LimitStrength value true");
      send(writer, "setoption name UCI_Elo value " + elo);
      send(writer, "position fen " + fen);
      send(writer, "go movetime " + moveTimeMillis);

      String bestMove = readBestMove(reader, fen);

      send(writer, "quit");
      // Enforce the hard wall-clock deadline on the whole exchange; if the process does not exit on
      // its own promptly after `quit`, the finally block force-kills it.
      if (!process.waitFor(deadlineMillis, TimeUnit.MILLISECONDS)) {
        throw new BotEngineException(
            "Stockfish did not exit within the " + deadlineMillis + "ms deadline");
      }
      return UciMove.parse(bestMove);
    } catch (IOException ex) {
      throw new BotEngineException("I/O error talking to Stockfish", ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new BotEngineException("Interrupted while waiting for Stockfish", ex);
    } catch (IllegalArgumentException ex) {
      // UciMove.parse / the domain smart constructors reject a malformed bestmove.
      throw new BotEngineException("Stockfish returned an unparseable move", ex);
    } finally {
      // Unconditional teardown: a normal exit makes this a no-op, a hung / timed-out / errored
      // process is force-killed here so no orphaned subprocess survives.
      process.destroyForcibly();
    }
  }

  /** Spawns the Stockfish process, mapping a spawn failure to a {@link BotEngineException}. */
  private Process spawn() {
    ProcessBuilder builder = new ProcessBuilder(properties.enginePath());
    // Fold stderr into stdout so a crash banner does not block on an unread stderr pipe.
    builder.redirectErrorStream(true);
    try {
      return builder.start();
    } catch (IOException ex) {
      throw new BotEngineException("Failed to spawn Stockfish at " + properties.enginePath(), ex);
    }
  }

  /** Writes one UCI command line and flushes it to the subprocess. */
  private static void send(BufferedWriter writer, String command) throws IOException {
    writer.write(command);
    writer.write('\n');
    writer.flush();
  }

  /**
   * Reads stdout lines until the {@code bestmove} line, returning the move token. The {@code go
   * movetime} bound keeps Stockfish from searching indefinitely, and the caller's {@code
   * waitFor(timeout)} + {@code finally} kill guard against a wedged process that never emits the
   * line.
   *
   * @throws BotEngineException if the stream ends before a {@code bestmove} line, or the engine
   *     reports {@code bestmove (none)} (no legal move — a finished position the bot should never
   *     be asked about).
   */
  private static String readBestMove(BufferedReader reader, String fen) throws IOException {
    String line;
    while ((line = reader.readLine()) != null) {
      if (line.startsWith("bestmove")) {
        String[] tokens = line.split("\\s+");
        if (tokens.length < 2 || "(none)".equals(tokens[1])) {
          throw new BotEngineException("Stockfish reported no legal move for position: " + fen);
        }
        return tokens[1];
      }
    }
    throw new BotEngineException("Stockfish closed its output before returning a bestmove");
  }

  /** Logs the configured engine path once at construction so a misconfigured path is visible. */
  @PostConstruct
  void logConfiguration() {
    log.info(
        "Stockfish bot engine configured: path={}, moveTime={}, moveTimeout={}",
        properties.enginePath(),
        properties.moveTime(),
        properties.moveTimeout());
  }
}
