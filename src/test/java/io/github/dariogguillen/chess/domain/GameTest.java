package io.github.dariogguillen.chess.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GameTest {

  private static final UUID ALICE_ID = UUID.fromString("00000000-0000-0000-0000-00000000000a");
  private static final UUID BOB_ID = UUID.fromString("00000000-0000-0000-0000-00000000000b");
  private static final UUID GAME_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final Player WHITE = new Player(ALICE_ID, "Alice");
  private static final Player BLACK = new Player(BOB_ID, "Bob");
  private static final String STARTING_FEN =
      "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
  private static final String CURRENT_FEN =
      "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1";
  private static final Move E2E4 = new Move(new Square("e2"), new Square("e4"), Optional.empty());

  @Test
  void shouldConstruct_whenAllFieldsAreValid() {
    Game game =
        new Game(
            GAME_ID,
            "r-1",
            WHITE,
            BLACK,
            STARTING_FEN,
            CURRENT_FEN,
            GameStatus.ONGOING,
            List.of(E2E4));

    assertThat(game.id()).isEqualTo(GAME_ID);
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
    Player aliceClone = new Player(ALICE_ID, "Alice the second");

    assertThatThrownBy(
            () ->
                new Game(
                    GAME_ID,
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
  void shouldThrowNullPointer_whenRoomIdIsNull() {
    assertThatNullPointerException()
        .isThrownBy(
            () ->
                new Game(
                    GAME_ID,
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
                    GAME_ID,
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
                    GAME_ID,
                    "r-1",
                    WHITE,
                    BLACK,
                    STARTING_FEN,
                    null,
                    GameStatus.ONGOING,
                    List.of()))
        .withMessage("fen");
  }

  @Test
  void shouldThrowNullPointer_whenStartingFenIsNull() {
    assertThatNullPointerException()
        .isThrownBy(
            () ->
                new Game(
                    GAME_ID,
                    "r-1",
                    WHITE,
                    BLACK,
                    null,
                    STARTING_FEN,
                    GameStatus.ONGOING,
                    List.of()))
        .withMessage("startingFen");
  }

  @Test
  void shouldReject_whenStartingFenIsBlank() {
    assertThatThrownBy(
            () ->
                new Game(
                    GAME_ID, "r-1", WHITE, BLACK, "", STARTING_FEN, GameStatus.ONGOING, List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("startingFen");
  }

  @Test
  void shouldThrowNullPointer_whenStatusIsNull() {
    assertThatNullPointerException()
        .isThrownBy(
            () ->
                new Game(GAME_ID, "r-1", WHITE, BLACK, STARTING_FEN, STARTING_FEN, null, List.of()))
        .withMessage("status");
  }

  @Test
  void shouldThrowNullPointer_whenMovesIsNull() {
    assertThatNullPointerException()
        .isThrownBy(
            () ->
                new Game(
                    GAME_ID,
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
            GAME_ID,
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
            GAME_ID, "r-1", WHITE, BLACK, STARTING_FEN, STARTING_FEN, GameStatus.ONGOING, mutable);
    mutable.add(new Move(new Square("e7"), new Square("e5"), Optional.empty()));

    assertThat(game.moves()).containsExactly(E2E4);
  }

  // ---- Feature 22 (time-control): nullable clock fields + all-or-nothing invariant ----

  @Test
  void shouldBeUntimed_whenBuiltWithTheEightArgConstructor() {
    Game game =
        new Game(
            GAME_ID,
            "r-1",
            WHITE,
            BLACK,
            STARTING_FEN,
            STARTING_FEN,
            GameStatus.ONGOING,
            List.of());

    assertThat(game.isTimed()).isFalse();
    assertThat(game.whiteTimeRemainingMs()).isNull();
    assertThat(game.blackTimeRemainingMs()).isNull();
    assertThat(game.lastMoveAt()).isNull();
    assertThat(game.incrementMs()).isNull();
  }

  @Test
  void shouldBeTimed_whenAllFourClockFieldsArePresent() {
    Instant anchor = Instant.parse("2026-05-29T10:00:00Z");
    Game game =
        new Game(
            GAME_ID,
            "r-1",
            WHITE,
            BLACK,
            STARTING_FEN,
            STARTING_FEN,
            GameStatus.ONGOING,
            List.of(),
            300_000L,
            300_000L,
            anchor,
            3_000L);

    assertThat(game.isTimed()).isTrue();
    assertThat(game.whiteTimeRemainingMs()).isEqualTo(300_000L);
    assertThat(game.blackTimeRemainingMs()).isEqualTo(300_000L);
    assertThat(game.lastMoveAt()).isEqualTo(anchor);
    assertThat(game.incrementMs()).isEqualTo(3_000L);
  }

  @Test
  void shouldReject_whenClockIsPartiallyInitialised() {
    Instant anchor = Instant.parse("2026-05-29T10:00:00Z");
    // White's time set, black's time null — a half-clock. The all-or-nothing invariant rejects it.
    assertThatThrownBy(
            () ->
                new Game(
                    GAME_ID,
                    "r-1",
                    WHITE,
                    BLACK,
                    STARTING_FEN,
                    STARTING_FEN,
                    GameStatus.ONGOING,
                    List.of(),
                    300_000L,
                    null,
                    anchor,
                    3_000L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("all-or-nothing");
  }

  @Test
  void withStatus_carriesTheClockFieldsThrough() {
    Instant anchor = Instant.parse("2026-05-29T10:00:00Z");
    Game timed =
        new Game(
            GAME_ID,
            "r-1",
            WHITE,
            BLACK,
            STARTING_FEN,
            STARTING_FEN,
            GameStatus.ONGOING,
            List.of(),
            120_000L,
            90_000L,
            anchor,
            2_000L);

    Game flagged = timed.withStatus(GameStatus.TIMEOUT);

    assertThat(flagged.status()).isEqualTo(GameStatus.TIMEOUT);
    assertThat(flagged.whiteTimeRemainingMs()).isEqualTo(120_000L);
    assertThat(flagged.blackTimeRemainingMs()).isEqualTo(90_000L);
    assertThat(flagged.lastMoveAt()).isEqualTo(anchor);
    assertThat(flagged.incrementMs()).isEqualTo(2_000L);
  }

  @Test
  void withClock_replacesStateButKeepsIncrement() {
    Instant anchor = Instant.parse("2026-05-29T10:00:00Z");
    Instant later = Instant.parse("2026-05-29T10:00:05Z");
    Game timed =
        new Game(
            GAME_ID,
            "r-1",
            WHITE,
            BLACK,
            STARTING_FEN,
            STARTING_FEN,
            GameStatus.ONGOING,
            List.of(),
            120_000L,
            90_000L,
            anchor,
            2_000L);

    Game advanced = timed.withClock(115_000L, 90_000L, later);

    assertThat(advanced.whiteTimeRemainingMs()).isEqualTo(115_000L);
    assertThat(advanced.blackTimeRemainingMs()).isEqualTo(90_000L);
    assertThat(advanced.lastMoveAt()).isEqualTo(later);
    // incrementMs is configuration, not state — withClock leaves it untouched.
    assertThat(advanced.incrementMs()).isEqualTo(2_000L);
  }
}
