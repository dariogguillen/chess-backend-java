package io.github.dariogguillen.chess.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * A chess move from one square to another, optionally with a promotion target.
 *
 * <p>The compact constructor enforces <em>structural</em> invariants only:
 *
 * <ul>
 *   <li>{@code from} and {@code to} must not be equal — a non-move is not a move.
 *   <li>{@code promotion} must not be {@code null}; use {@link Optional#empty()} for non-promotion
 *       moves.
 *   <li>If a promotion is present, it must be a legal promotion target (see {@link
 *       Piece#isPromotionTarget()}). Promoting to a pawn or a king is not well-defined.
 * </ul>
 *
 * <p>Chess legality (whether the move is reachable from the current board, whether the piece on
 * {@code from} is actually a pawn that can promote, etc.) is enforced by the {@code ChessRules}
 * service with chesslib. The domain layer does not look at boards.
 *
 * @param from the origin square; must not equal {@code to}.
 * @param to the destination square.
 * @param promotion the promotion target; {@link Optional#empty()} for non-promotion moves;
 *     otherwise must satisfy {@link Piece#isPromotionTarget()}.
 */
public record Move(Square from, Square to, Optional<Piece> promotion) {

  public Move {
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
    Objects.requireNonNull(promotion, "promotion");
    if (from.equals(to)) {
      throw new IllegalArgumentException("Move from and to must differ, got: " + from);
    }
    promotion.ifPresent(
        piece -> {
          if (!piece.isPromotionTarget()) {
            throw new IllegalArgumentException(
                "Promotion target must be one of KNIGHT, BISHOP, ROOK, QUEEN, got: " + piece);
          }
        });
  }
}
