package io.github.dariogguillen.chess.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SquareTest {

  @ParameterizedTest
  @ValueSource(strings = {"a1", "h8", "e4", "a8", "h1", "d5"})
  void shouldConstruct_whenValueIsValidAlgebraicNotation(String value) {
    Square square = new Square(value);

    assertThat(square.value()).isEqualTo(value);
  }

  @Test
  void shouldThrowNullPointer_whenValueIsNull() {
    assertThatNullPointerException().isThrownBy(() -> new Square(null)).withMessage("value");
  }

  @Test
  void shouldRejectBlank() {
    assertThatThrownBy(() -> new Square("")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Square("  ")).isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"a", "a11", "abc", "1", "abcd"})
  void shouldReject_whenLengthIsNotTwo(String value) {
    assertThatThrownBy(() -> new Square(value))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exactly 2 characters");
  }

  @ParameterizedTest
  @ValueSource(strings = {"i1", "z5", "`1", "11", "@8"})
  void shouldReject_whenFileIsOutOfRange(String value) {
    assertThatThrownBy(() -> new Square(value))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("file");
  }

  @ParameterizedTest
  @ValueSource(strings = {"a0", "a9", "h0", "h9", "e!"})
  void shouldReject_whenRankIsOutOfRange(String value) {
    assertThatThrownBy(() -> new Square(value))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("rank");
  }

  @ParameterizedTest
  @ValueSource(strings = {"A1", "H8", "E4"})
  void shouldReject_whenFileIsUppercase(String value) {
    // Decision: we reject uppercase rather than normalizing. See Square's JavaDoc and
    // notes/02-domain-models.md "Decisions taken".
    assertThatThrownBy(() -> new Square(value))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("file");
  }

  @Test
  void shouldBeEqual_whenSameValue() {
    assertThat(new Square("e4")).isEqualTo(new Square("e4"));
    assertThat(new Square("e4")).isNotEqualTo(new Square("e5"));
  }
}
