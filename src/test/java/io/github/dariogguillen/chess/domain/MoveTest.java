package io.github.dariogguillen.chess.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class MoveTest {

  @Test
  void shouldConstruct_whenNoPromotion() {
    Move move = new Move(new Square("e2"), new Square("e4"), Optional.empty());

    assertThat(move.from()).isEqualTo(new Square("e2"));
    assertThat(move.to()).isEqualTo(new Square("e4"));
    assertThat(move.promotion()).isEmpty();
  }

  @ParameterizedTest
  @EnumSource(
      value = Piece.class,
      names = {"KNIGHT", "BISHOP", "ROOK", "QUEEN"})
  void shouldConstruct_whenPromotionIsLegalTarget(Piece target) {
    Move move = new Move(new Square("e7"), new Square("e8"), Optional.of(target));

    assertThat(move.promotion()).contains(target);
  }

  @Test
  void shouldReject_whenFromEqualsTo() {
    Square e4 = new Square("e4");
    assertThatThrownBy(() -> new Move(e4, e4, Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("differ");
  }

  @Test
  void shouldReject_whenPromotionIsPawn() {
    assertThatThrownBy(() -> new Move(new Square("e7"), new Square("e8"), Optional.of(Piece.PAWN)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Promotion");
  }

  @Test
  void shouldReject_whenPromotionIsKing() {
    assertThatThrownBy(() -> new Move(new Square("e7"), new Square("e8"), Optional.of(Piece.KING)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Promotion");
  }

  @Test
  void shouldThrowNullPointer_whenPromotionIsNull() {
    assertThatNullPointerException()
        .isThrownBy(() -> new Move(new Square("e2"), new Square("e4"), null))
        .withMessage("promotion");
  }

  @Test
  void shouldThrowNullPointer_whenFromIsNull() {
    assertThatNullPointerException()
        .isThrownBy(() -> new Move(null, new Square("e4"), Optional.empty()))
        .withMessage("from");
  }

  @Test
  void shouldThrowNullPointer_whenToIsNull() {
    assertThatNullPointerException()
        .isThrownBy(() -> new Move(new Square("e2"), null, Optional.empty()))
        .withMessage("to");
  }
}
