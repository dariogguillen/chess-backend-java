package io.github.dariogguillen.chess.domain;

/**
 * The outcome-bearing status of a {@link Game}.
 *
 * <ul>
 *   <li>{@link #ONGOING} — the game is in progress and the side to move is not in check.
 *   <li>{@link #CHECK} — the game is in progress and the side to move is in check.
 *   <li>{@link #CHECKMATE} — the side to move has no legal moves and is in check; the game ends.
 *   <li>{@link #STALEMATE} — the side to move has no legal moves and is not in check; the game is a
 *       draw.
 *   <li>{@link #DRAW} — the game ended in a non-stalemate draw (50-move rule, threefold repetition,
 *       insufficient material, agreement).
 *   <li>{@link #ABANDONED} — a player left and the grace period elapsed; the opponent wins by
 *       abandonment.
 * </ul>
 */
public enum GameStatus {
  ONGOING,
  CHECK,
  CHECKMATE,
  STALEMATE,
  DRAW,
  ABANDONED;

  /** Whether this status means the game has ended (no further moves allowed). */
  public boolean isTerminal() {
    return this == CHECKMATE || this == STALEMATE || this == DRAW || this == ABANDONED;
  }
}
