package io.github.dariogguillen.chess.domain;

import java.util.Objects;

/**
 * A board square in algebraic notation, lowercase, from {@code "a1"} to {@code "h8"}.
 *
 * <p>Once you hold a {@code Square}, you know its value is syntactically valid. This is the
 * canonical "smart constructor" pattern, implemented in Java via a record's compact constructor.
 *
 * <p>The constructor rejects uppercase input (e.g. {@code "A1"}) rather than normalizing to
 * lowercase. Normalizing would mean two {@code Square}s built from different strings could be
 * {@code equal}, which is surprising for a record whose {@code equals} is component-wise. Rejecting
 * is the simpler invariant and pushes the responsibility of producing a canonical form to the
 * caller (and to any future deserializer).
 *
 * @param value the algebraic notation of the square, e.g. {@code "e4"}.
 */
public record Square(String value) {

  public Square {
    Objects.requireNonNull(value, "value");
    if (value.length() != 2) {
      throw new IllegalArgumentException(
          "Square must be exactly 2 characters (file + rank), got: '" + value + "'");
    }
    char file = value.charAt(0);
    char rank = value.charAt(1);
    if (file < 'a' || file > 'h') {
      throw new IllegalArgumentException(
          "Square file must be lowercase 'a'..'h', got: '" + value + "'");
    }
    if (rank < '1' || rank > '8') {
      throw new IllegalArgumentException("Square rank must be '1'..'8', got: '" + value + "'");
    }
  }
}
