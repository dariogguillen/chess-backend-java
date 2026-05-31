package io.github.dariogguillen.chess.service.bot;

import io.github.dariogguillen.chess.domain.Move;
import io.github.dariogguillen.chess.domain.Piece;
import io.github.dariogguillen.chess.domain.Square;
import java.util.Optional;

/**
 * Parser from a UCI long-algebraic move string to the domain {@link Move} (feature 23, {@code
 * bot-opponent}).
 *
 * <p>UCI ({@code bestmove}) emits a move as the from-square and to-square concatenated, optionally
 * followed by a lowercase promotion letter:
 *
 * <ul>
 *   <li>{@code "e2e4"} — a normal move.
 *   <li>{@code "e7e8q"} — a promotion to queen ({@code q}/{@code r}/{@code b}/{@code n}).
 *   <li>{@code "e1g1"} — castling, expressed as the king's two-square move (chesslib reconstructs
 *       the rook move; nothing special is needed here).
 * </ul>
 *
 * <p>This is a pure, side-effect-free utility — the natural shape is a {@code final class} with a
 * private constructor and a single static method, since there is no state to hold and nothing to
 * inject. The squares and the promotion piece are funnelled through the domain {@link Square} /
 * {@link Piece} / {@link Move} smart constructors, so a malformed UCI string surfaces as an {@link
 * IllegalArgumentException} (from the underlying record validation) — {@code BotMoveService} treats
 * that, like any other engine failure, as the engine-failure terminal path.
 */
public final class UciMove {

  private UciMove() {}

  /**
   * Parses a UCI move string into a domain {@link Move}.
   *
   * @param uci the UCI move, e.g. {@code "e2e4"} or {@code "e7e8q"}; lowercase, 4 or 5 characters.
   * @return the parsed {@link Move}.
   * @throws IllegalArgumentException if {@code uci} is null, not 4 or 5 characters, carries an
   *     out-of-board square, or has an unrecognised promotion letter. Includes the special UCI
   *     "null move" {@code "0000"}, which a healthy engine never emits as a bestmove for a live
   *     position.
   */
  public static Move parse(String uci) {
    if (uci == null || (uci.length() != 4 && uci.length() != 5)) {
      throw new IllegalArgumentException(
          "UCI move must be 4 or 5 characters (e.g. e2e4, e7e8q), got: " + uci);
    }
    Square from = new Square(uci.substring(0, 2));
    Square to = new Square(uci.substring(2, 4));
    Optional<Piece> promotion =
        uci.length() == 5 ? Optional.of(promotionFromLetter(uci.charAt(4))) : Optional.empty();
    return new Move(from, to, promotion);
  }

  /**
   * Maps a UCI promotion letter to the domain {@link Piece}. UCI promotion letters are always
   * lowercase and limited to the four legal targets.
   */
  private static Piece promotionFromLetter(char letter) {
    return switch (letter) {
      case 'q' -> Piece.QUEEN;
      case 'r' -> Piece.ROOK;
      case 'b' -> Piece.BISHOP;
      case 'n' -> Piece.KNIGHT;
      default ->
          throw new IllegalArgumentException(
              "Unrecognised UCI promotion letter (expected one of q, r, b, n): " + letter);
    };
  }
}
