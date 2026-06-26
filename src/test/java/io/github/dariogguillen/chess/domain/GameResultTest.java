package io.github.dariogguillen.chess.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link GameResult} winner-derivation helpers — the single derivation the four
 * terminal paths and the broadcast {@code winnerId} all funnel through. Pure logic, no Spring
 * context.
 *
 * <p>Two helpers, two mappings:
 *
 * <ul>
 *   <li>{@link GameResult#fromWinner(Game, UUID)} — by the abandon / bot-failure paths, which know
 *       the winner id directly: white id -> WHITE_WIN, black id -> BLACK_WIN, null -> DRAW.
 *   <li>{@link GameResult#fromLoserToMove(boolean)} — by the clock/checkmate paths, which know the
 *       side TO MOVE (the loser): white to move -> BLACK_WIN, black to move -> WHITE_WIN.
 * </ul>
 */
class GameResultTest {

  private static final String FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

  private static Game gameOf(UUID whiteId, UUID blackId) {
    return new Game(
        UUID.randomUUID(),
        "ROOM01",
        new Player(whiteId, "White"),
        new Player(blackId, "Black"),
        FEN,
        FEN,
        GameStatus.ABANDONED,
        List.of());
  }

  @Test
  void fromWinner_whiteId_isWhiteWin() {
    UUID whiteId = UUID.randomUUID();
    UUID blackId = UUID.randomUUID();
    Game game = gameOf(whiteId, blackId);

    assertThat(GameResult.fromWinner(game, whiteId)).isEqualTo(GameResult.WHITE_WIN);
  }

  @Test
  void fromWinner_blackId_isBlackWin() {
    UUID whiteId = UUID.randomUUID();
    UUID blackId = UUID.randomUUID();
    Game game = gameOf(whiteId, blackId);

    assertThat(GameResult.fromWinner(game, blackId)).isEqualTo(GameResult.BLACK_WIN);
  }

  @Test
  void fromWinner_nullWinner_isDraw() {
    Game game = gameOf(UUID.randomUUID(), UUID.randomUUID());

    assertThat(GameResult.fromWinner(game, null)).isEqualTo(GameResult.DRAW);
  }

  @Test
  void fromWinner_unknownWinner_throws() {
    Game game = gameOf(UUID.randomUUID(), UUID.randomUUID());

    assertThatThrownBy(() -> GameResult.fromWinner(game, UUID.randomUUID()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("matches neither white nor black");
  }

  @Test
  void fromLoserToMove_whiteToMove_isBlackWin() {
    // White is to move (and lost — mated or flagged), so black won.
    assertThat(GameResult.fromLoserToMove(true)).isEqualTo(GameResult.BLACK_WIN);
  }

  @Test
  void fromLoserToMove_blackToMove_isWhiteWin() {
    assertThat(GameResult.fromLoserToMove(false)).isEqualTo(GameResult.WHITE_WIN);
  }
}
