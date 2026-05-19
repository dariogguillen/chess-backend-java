package io.github.dariogguillen.chess.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GameTest {

  private static final Player WHITE = new Player("alice", "Alice");
  private static final Player BLACK = new Player("bob", "Bob");
  private static final String STARTING_FEN =
      "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
  private static final String CURRENT_FEN =
      "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1";
  private static final Move E2E4 = new Move(new Square("e2"), new Square("e4"), Optional.empty());

  @Test
  void shouldConstruct_whenAllFieldsAreValid() {
    Game game =
        new Game(
            "g-1",
            "r-1",
            WHITE,
            BLACK,
            STARTING_FEN,
            CURRENT_FEN,
            GameStatus.ONGOING,
            List.of(E2E4));

    assertThat(game.id()).isEqualTo("g-1");
    assertThat(game.roomId()).isEqualTo("r-1");
    assertThat(game.white()).isEqualTo(WHITE);
    assertThat(game.black()).isEqualTo(BLACK);
    assertThat(game.startingFen()).isEqualTo(STARTING_FEN);
    assertThat(game.fen()).isEqualTo(CURRENT_FEN);
    assertThat(game.status()).isEqualTo(GameStatus.ONGOING);
    assertThat(game.moves()).containsExactly(E2E4);
  }

  @Test
  void shouldReject_whenSamePlayerOnBothSides() {
    Player aliceClone = new Player("alice", "Alice the second");

    assertThatThrownBy(
            () ->
                new Game(
                    "g-1",
                    "r-1",
                    WHITE,
                    aliceClone,
                    STARTING_FEN,
                    STARTING_FEN,
                    GameStatus.ONGOING,
                    List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("distinct");
  }

  @Test
  void shouldThrowNullPointer_whenIdIsNull() {
    assertThatNullPointerException()
        .isThrownBy(
            () ->
                new Game(
                    null,
                    "r-1",
                    WHITE,
                    BLACK,
                    STARTING_FEN,
                    STARTING_FEN,
                    GameStatus.ONGOING,
                    List.of()))
        .withMessage("id");
  }

  @Test
  void shouldReject_whenIdIsBlank() {
    assertThatThrownBy(
            () ->
                new Game(
                    " ",
                    "r-1",
                    WHITE,
                    BLACK,
                    STARTING_FEN,
                    STARTING_FEN,
                    GameStatus.ONGOING,
                    List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Game id");
  }

  @Test
  void shouldThrowNullPointer_whenRoomIdIsNull() {
    assertThatNullPointerException()
        .isThrownBy(
            () ->
                new Game(
                    "g-1",
                    null,
                    WHITE,
                    BLACK,
                    STARTING_FEN,
                    STARTING_FEN,
                    GameStatus.ONGOING,
                    List.of()))
        .withMessage("roomId");
  }

  @Test
  void shouldReject_whenRoomIdIsBlank() {
    assertThatThrownBy(
            () ->
                new Game(
                    "g-1",
                    "",
                    WHITE,
                    BLACK,
                    STARTING_FEN,
                    STARTING_FEN,
                    GameStatus.ONGOING,
                    List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("roomId");
  }

  @Test
  void shouldThrowNullPointer_whenFenIsNull() {
    assertThatNullPointerException()
        .isThrownBy(
            () ->
                new Game(
                    "g-1", "r-1", WHITE, BLACK, STARTING_FEN, null, GameStatus.ONGOING, List.of()))
        .withMessage("fen");
  }

  @Test
  void shouldThrowNullPointer_whenStartingFenIsNull() {
    assertThatNullPointerException()
        .isThrownBy(
            () ->
                new Game(
                    "g-1", "r-1", WHITE, BLACK, null, STARTING_FEN, GameStatus.ONGOING, List.of()))
        .withMessage("startingFen");
  }

  @Test
  void shouldReject_whenStartingFenIsBlank() {
    assertThatThrownBy(
            () ->
                new Game(
                    "g-1", "r-1", WHITE, BLACK, "", STARTING_FEN, GameStatus.ONGOING, List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("startingFen");
  }

  @Test
  void shouldThrowNullPointer_whenStatusIsNull() {
    assertThatNullPointerException()
        .isThrownBy(
            () -> new Game("g-1", "r-1", WHITE, BLACK, STARTING_FEN, STARTING_FEN, null, List.of()))
        .withMessage("status");
  }

  @Test
  void shouldThrowNullPointer_whenMovesIsNull() {
    assertThatNullPointerException()
        .isThrownBy(
            () ->
                new Game(
                    "g-1",
                    "r-1",
                    WHITE,
                    BLACK,
                    STARTING_FEN,
                    STARTING_FEN,
                    GameStatus.ONGOING,
                    null))
        .withMessage("moves");
  }

  @Test
  void shouldReturnUnmodifiableView_fromMoves() {
    Game game =
        new Game(
            "g-1",
            "r-1",
            WHITE,
            BLACK,
            STARTING_FEN,
            STARTING_FEN,
            GameStatus.ONGOING,
            List.of(E2E4));

    Move e7e5 = new Move(new Square("e7"), new Square("e5"), Optional.empty());
    assertThatThrownBy(() -> game.moves().add(e7e5))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void shouldDefensivelyCopy_whenCallerMutatesInputList() {
    List<Move> mutable = new ArrayList<>();
    mutable.add(E2E4);

    Game game =
        new Game(
            "g-1", "r-1", WHITE, BLACK, STARTING_FEN, STARTING_FEN, GameStatus.ONGOING, mutable);
    mutable.add(new Move(new Square("e7"), new Square("e5"), Optional.empty()));

    assertThat(game.moves()).containsExactly(E2E4);
  }
}
