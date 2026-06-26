package io.github.dariogguillen.chess.domain;

import java.util.UUID;

/**
 * Who won an archived (terminal-status) {@link Game}, persisted alongside {@link GameStatus} so
 * per-user W/L/D statistics become possible. Mirrors {@link GameStatus}'s style: a plain enum
 * stored via {@code @Enumerated(EnumType.STRING)} (the column keeps the constant's {@code name()},
 * e.g. {@code "WHITE_WIN"}).
 *
 * <p>Where {@link GameStatus} answers <em>how</em> the game ended (checkmate, stalemate, draw,
 * abandonment, timeout), {@link GameResult} answers <em>who</em> ended up the winner. The two are
 * orthogonal: a {@link GameStatus#TIMEOUT} can be either a {@link #WHITE_WIN} or a {@link
 * #BLACK_WIN} depending on which side flagged, and {@link GameStatus#STALEMATE} and {@link
 * GameStatus#DRAW} both map to {@link #DRAW}.
 *
 * <p>The constant names are load-bearing: the {@code V4__add_game_result.sql} backfill writes these
 * exact string literals and the {@code @Schema(allowableValues = ...)} on the wire DTO mirrors
 * them.
 *
 * <ul>
 *   <li>{@link #WHITE_WIN} — the white side won.
 *   <li>{@link #BLACK_WIN} — the black side won.
 *   <li>{@link #DRAW} — neither side won (stalemate, or any draw rule).
 * </ul>
 *
 * <p>A non-terminal game has no result — the {@link Game} component is {@code null} until a
 * terminal transition sets it, and legacy archived rows whose winner is unrecoverable (an {@code
 * ABANDONED} game predating this feature) stay {@code null} too.
 */
public enum GameResult {
  WHITE_WIN,
  BLACK_WIN,
  DRAW;

  /**
   * Derives the {@link GameResult} from the id of the winning {@link Player} relative to a terminal
   * game's two sides. This is the single derivation the four terminal paths and the broadcast
   * {@code winnerId} all funnel through, so the white-vs-black comparison lives in exactly one
   * place rather than being re-spelled at each call site.
   *
   * <p>A {@code null} {@code winnerId} means a draw (stalemate or any draw rule), so the caller can
   * pass {@code null} on the draw branch and a concrete winner id otherwise.
   *
   * @param game the terminal game whose white/black ids the winner is matched against; non-null.
   * @param winnerId the id of the winning player, or {@code null} for a draw.
   * @return {@link #DRAW} when {@code winnerId} is {@code null}; otherwise {@link #WHITE_WIN} if
   *     the winner is white, {@link #BLACK_WIN} if the winner is black.
   * @throws IllegalArgumentException if {@code winnerId} is non-null but matches neither side.
   */
  public static GameResult fromWinner(Game game, UUID winnerId) {
    if (winnerId == null) {
      return DRAW;
    }
    if (winnerId.equals(game.white().id())) {
      return WHITE_WIN;
    }
    if (winnerId.equals(game.black().id())) {
      return BLACK_WIN;
    }
    throw new IllegalArgumentException(
        "winnerId " + winnerId + " matches neither white nor black of game " + game.id());
  }

  /**
   * Derives the {@link GameResult} for a game that ended by a side running out of legal moves or
   * forfeiting on the clock, from the side that was <em>to move</em> at the terminal position.
   *
   * <p>This is the {@link GameStatus#CHECKMATE}/{@link GameStatus#TIMEOUT} mapping: the side to
   * move is the side that was mated (no legal response to check) or that flagged (ran out of time),
   * i.e. the loser — so the <em>other</em> side wins. White to move means white lost.
   *
   * @param whiteToMove whether white was the side to move at the terminal position.
   * @return {@link #BLACK_WIN} when white was to move (white lost), {@link #WHITE_WIN} otherwise.
   */
  public static GameResult fromLoserToMove(boolean whiteToMove) {
    return whiteToMove ? BLACK_WIN : WHITE_WIN;
  }
}
