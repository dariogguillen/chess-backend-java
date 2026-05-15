package io.github.dariogguillen.chess.domain;

/**
 * The six chess piece kinds.
 *
 * <p>This enum is intentionally a single, honest enum over all pieces. A separate {@code
 * PromotionPiece} enum with only the four legal promotion targets would buy compile-time guarantees
 * in exactly one place ({@link Move#promotion()}) at the cost of constant conversions in every
 * other place a piece appears (FEN parsing, captures, board representation). The trade-off favors
 * one enum plus runtime validation in {@link Move}.
 */
public enum Piece {
  PAWN,
  KNIGHT,
  BISHOP,
  ROOK,
  QUEEN,
  KING;

  /**
   * Whether this piece is a legal target of a pawn promotion.
   *
   * <p>Per the rules of chess, a pawn reaching the last rank must promote to a knight, bishop,
   * rook, or queen. Promoting to a pawn or a king is not defined at any level (structural or
   * legality), so {@link Move}'s compact constructor uses this method to reject malformed
   * promotions.
   *
   * @return {@code true} for {@link #KNIGHT}, {@link #BISHOP}, {@link #ROOK}, {@link #QUEEN};
   *     {@code false} for {@link #PAWN} and {@link #KING}.
   */
  public boolean isPromotionTarget() {
    return this == KNIGHT || this == BISHOP || this == ROOK || this == QUEEN;
  }
}
