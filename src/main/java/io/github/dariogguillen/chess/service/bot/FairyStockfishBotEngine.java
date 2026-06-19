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
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Production {@link BotEngine} adapter that drives a <strong>Fairy-Stockfish</strong> subprocess
 * over the UCI protocol (feature 23.7, {@code bot-strength-fairy-stockfish}; originally feature 23
 * {@code bot-opponent} against official Stockfish).
 *
 * <p><strong>Why Fairy-Stockfish.</strong> Feature 23.5 limited strength with official Stockfish's
 * {@code UCI_LimitStrength}/{@code UCI_Elo}, whose floor is ~1320 — it cannot play like a beginner.
 * This adapter follows Lichess's model instead: Fairy-Stockfish's {@code Skill Level} option has
 * the extended range {@code -20..20}, and the negative levels are what reach sub-1320 play. The
 * requested Elo is translated to a {@code (Skill Level, depth)} pair by {@link BotEloMapping} (a
 * port of lila's fishnet table), and the search is bounded by {@code go depth <d>} rather than
 * {@code go movetime}.
 *
 * <p><strong>Spawn-per-move lifecycle.</strong> Each {@link #chooseMove(String, int)} call spawns a
 * fresh engine process via {@link ProcessBuilder}, runs one UCI handshake + search, parses the
 * {@code bestmove}, and tears the process down. The engine is stateless (the FEN fully describes
 * the position), so there is no per-game process to track and no shared mutable state — robust on a
 * memory-constrained t3.micro and trivially correct under concurrency (the dedicated bot executor
 * may run several searches at once, each in its own process).
 *
 * <p><strong>Guaranteed cleanup.</strong> The process is <em>always</em> torn down in a {@code
 * finally} block via {@link Process#destroyForcibly()} — even on a timeout, an I/O error, or an
 * unparseable response. Combined with the {@code waitFor(timeout)} hard deadline this guarantees no
 * orphaned engine process survives a failed search. This is the JVM idiom equivalent of a Typelevel
 * {@code Resource[F, Process]} with a guaranteed release.
 *
 * <p>The UCI exchange is the minimum that produces a move at the mapped strength:
 *
 * <pre>
 *   &gt; uci
 *   &lt; ... uciok
 *   &gt; isready
 *   &gt; setoption name Use NNUE value false
 *   &gt; setoption name Skill Level value &lt;s&gt;
 *   &gt; position fen &lt;fen&gt;
 *   &gt; go depth &lt;d&gt;
 *   &lt; ... bestmove e2e4 ponder ...
 *   &gt; quit
 * </pre>
 *
 * <p><strong>Classical eval ({@code Use NNUE false}).</strong> Disabling NNUE makes the engine use
 * its built-in classical evaluation, so the Docker image needs <em>not</em> ship a multi-megabyte
 * NNUE net file alongside the binary. The {@code Skill Level} weakening operates the same way under
 * either evaluation; classical is plenty for portfolio-scale bot play.
 *
 * <p>Any deviation (spawn failure, the deadline elapsing before {@code bestmove}, a {@code bestmove
 * (none)} for a finished position, or a malformed line) is surfaced as a {@link
 * BotEngineException}.
 */
@Component
public class FairyStockfishBotEngine implements BotEngine {

  private static final Logger log = LoggerFactory.getLogger(FairyStockfishBotEngine.class);

  private final BotProperties properties;

  public FairyStockfishBotEngine(BotProperties properties) {
    this.properties = properties;
  }

  @Override
  public Move chooseMove(String fen, int elo) {
    long deadlineMillis = properties.moveTimeout().toMillis();
    List<String> commands = buildSearchCommands(fen, elo);

    Process process = spawn();
    try (BufferedWriter writer =
            new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        BufferedReader reader =
            new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {

      for (String command : commands) {
        send(writer, command);
      }

      String bestMove = readBestMove(reader, fen);

      send(writer, "quit");
      // Enforce the hard wall-clock deadline on the whole exchange; if the process does not exit on
      // its own promptly after `quit`, the finally block force-kills it.
      if (!process.waitFor(deadlineMillis, TimeUnit.MILLISECONDS)) {
        throw new BotEngineException(
            "Fairy-Stockfish did not exit within the " + deadlineMillis + "ms deadline");
      }
      return UciMove.parse(bestMove);
    } catch (IOException ex) {
      throw new BotEngineException("I/O error talking to Fairy-Stockfish", ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new BotEngineException("Interrupted while waiting for Fairy-Stockfish", ex);
    } catch (IllegalArgumentException ex) {
      // UciMove.parse / the domain smart constructors reject a malformed bestmove.
      throw new BotEngineException("Fairy-Stockfish returned an unparseable move", ex);
    } finally {
      // Unconditional teardown: a normal exit makes this a no-op, a hung / timed-out / errored
      // process is force-killed here so no orphaned subprocess survives.
      process.destroyForcibly();
    }
  }

  /**
   * Builds the ordered UCI command lines for one search at the target strength. Factored out as the
   * unit-testable seam: it lets a test assert the exact option exchange ({@code Skill Level} +
   * {@code go depth}, and the <em>absence</em> of {@code UCI_Elo}) for a given Elo without spawning
   * the real binary. UCI requires options to be set on a ready engine before the search, hence the
   * {@code isready} between the handshake and the {@code setoption} lines.
   *
   * @param fen the position to search.
   * @param elo the target strength, mapped to {@code (Skill Level, depth)} via {@link
   *     BotEloMapping}.
   * @return the command lines to send, in order (excluding the trailing {@code quit}).
   */
  List<String> buildSearchCommands(String fen, int elo) {
    EngineStrength strength = BotEloMapping.forElo(elo);
    return List.of(
        "uci",
        "isready",
        // Classical eval so the image need not ship an NNUE net file.
        "setoption name Use NNUE value false",
        // The single Lichess weakening knob; Fairy's negative levels reach sub-1320 play.
        "setoption name Skill Level value " + strength.skillLevel(),
        "position fen " + fen,
        // Bound the search by depth (the Lichess tier model), not wall-clock movetime.
        "go depth " + strength.depth());
  }

  /** Spawns the engine process, mapping a spawn failure to a {@link BotEngineException}. */
  private Process spawn() {
    ProcessBuilder builder = new ProcessBuilder(properties.enginePath());
    // Fold stderr into stdout so a crash banner does not block on an unread stderr pipe.
    builder.redirectErrorStream(true);
    try {
      return builder.start();
    } catch (IOException ex) {
      throw new BotEngineException(
          "Failed to spawn Fairy-Stockfish at " + properties.enginePath(), ex);
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
   * depth} bound keeps the engine from searching indefinitely, and the caller's {@code
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
          throw new BotEngineException(
              "Fairy-Stockfish reported no legal move for position: " + fen);
        }
        return tokens[1];
      }
    }
    throw new BotEngineException("Fairy-Stockfish closed its output before returning a bestmove");
  }

  /** Logs the configured engine path once at construction so a misconfigured path is visible. */
  @PostConstruct
  void logConfiguration() {
    log.info(
        "Fairy-Stockfish bot engine configured: path={}, moveTimeout={}",
        properties.enginePath(),
        properties.moveTimeout());
  }
}
