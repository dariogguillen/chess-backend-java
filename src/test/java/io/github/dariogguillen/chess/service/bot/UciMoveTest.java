package io.github.dariogguillen.chess.service.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dariogguillen.chess.domain.Move;
import io.github.dariogguillen.chess.domain.Piece;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit coverage for {@link UciMove}, the UCI-string → domain {@link Move} parser (feature 23,
 * `bot-opponent`). The parser is pure logic with non-trivial branching (square slicing, optional
 * promotion letter, malformed-input rejection) that the {@code BotGameIT} cannot exercise
 * exhaustively without driving a real engine — a textbook unit-test fit per the conventions.
 */
class UciMoveTest {

  @Test
  void parse_normalMove_buildsFromToWithoutPromotion() {
    Move move = UciMove.parse("e2e4");

    assertThat(move.from().value()).isEqualTo("e2");
    assertThat(move.to().value()).isEqualTo("e4");
    assertThat(move.promotion()).isEmpty();
  }

  @ParameterizedTest
  @CsvSource({"e7e8q,QUEEN", "e7e8r,ROOK", "e7e8b,BISHOP", "e7e8n,KNIGHT"})
  void parse_promotion_mapsEveryLegalTarget(String uci, Piece expected) {
    Move move = UciMove.parse(uci);

    assertThat(move.from().value()).isEqualTo("e7");
    assertThat(move.to().value()).isEqualTo("e8");
    assertThat(move.promotion()).isEqualTo(Optional.of(expected));
  }

  @Test
  void parse_castling_isJustTheKingTwoSquareMove() {
    // UCI expresses castling as the king's move; chesslib reconstructs the rook move downstream.
    Move move = UciMove.parse("e1g1");

    assertThat(move.from().value()).isEqualTo("e1");
    assertThat(move.to().value()).isEqualTo("g1");
    assertThat(move.promotion()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"e2", "e2e4e5", "0000", "z9z8", "e7e8k", "e7e8p", "e2e4Q"})
  void parse_malformed_isRejected(String uci) {
    assertThatThrownBy(() -> UciMove.parse(uci)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void parse_null_isRejected() {
    assertThatThrownBy(() -> UciMove.parse(null)).isInstanceOf(IllegalArgumentException.class);
  }
}
