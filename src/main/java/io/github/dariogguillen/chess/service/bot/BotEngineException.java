package io.github.dariogguillen.chess.service.bot;

/**
 * Thrown by a {@link BotEngine} adapter when it cannot produce a move: the production {@link
 * StockfishBotEngine} raises it on subprocess spawn failure, a UCI handshake / search timeout (the
 * {@code waitFor(timeout)} deadline elapses and the process is force-killed), a non-zero process
 * exit, or an unparseable {@code bestmove} line.
 *
 * <p>It is a {@link RuntimeException} so the async bot task does not have to declare it; {@code
 * BotMoveService} catches it (together with any other failure surfaced while applying the move) and
 * terminates the game on the engine-failure path — status {@code ABANDONED} with the human as
 * winner plus a {@code GameEngineFailedEvent} broadcast — rather than letting the JVM swallow the
 * exception on the executor thread.
 */
public class BotEngineException extends RuntimeException {

  /**
   * @param message a human-readable description of what failed (no FEN or move detail that would
   *     bloat the log; the caller adds the {@code gameId}).
   */
  public BotEngineException(String message) {
    super(message);
  }

  /**
   * @param message a human-readable description of what failed.
   * @param cause the underlying failure (an {@code IOException} from {@code ProcessBuilder}, an
   *     {@code InterruptedException} from {@code waitFor}, etc.).
   */
  public BotEngineException(String message, Throwable cause) {
    super(message, cause);
  }
}
