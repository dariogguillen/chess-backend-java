package io.github.dariogguillen.chess.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RoomTest {

  private static final UUID ALICE_ID = UUID.fromString("00000000-0000-0000-0000-00000000000a");
  private static final UUID BOB_ID = UUID.fromString("00000000-0000-0000-0000-00000000000b");
  private static final UUID CHARLIE_ID = UUID.fromString("00000000-0000-0000-0000-00000000000c");
  private static final Player ALICE = new Player(ALICE_ID, "Alice");
  private static final Player BOB = new Player(BOB_ID, "Bob");
  private static final Player CHARLIE = new Player(CHARLIE_ID, "Charlie");

  @Test
  void shouldConstruct_whenZeroPlayersAndWaiting() {
    Room room = new Room("r-1", List.of(), RoomStatus.WAITING_FOR_PLAYER);

    assertThat(room.players()).isEmpty();
    assertThat(room.status()).isEqualTo(RoomStatus.WAITING_FOR_PLAYER);
  }

  @Test
  void shouldConstruct_whenOnePlayerAndWaiting() {
    Room room = new Room("r-1", List.of(ALICE), RoomStatus.WAITING_FOR_PLAYER);

    assertThat(room.players()).containsExactly(ALICE);
  }

  @Test
  void shouldConstruct_whenTwoPlayersAndActive() {
    Room room = new Room("r-1", List.of(ALICE, BOB), RoomStatus.ACTIVE);

    assertThat(room.players()).containsExactly(ALICE, BOB);
    assertThat(room.status()).isEqualTo(RoomStatus.ACTIVE);
  }

  @Test
  void shouldReject_whenMoreThanTwoPlayers() {
    assertThatThrownBy(() -> new Room("r-1", List.of(ALICE, BOB, CHARLIE), RoomStatus.ACTIVE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at most 2 players");
  }

  @Test
  void shouldReject_whenDuplicatePlayerId() {
    Player aliceClone = new Player(ALICE_ID, "Alice the second");

    assertThatThrownBy(
            () -> new Room("r-1", List.of(ALICE, aliceClone), RoomStatus.WAITING_FOR_PLAYER))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicate");
  }

  @Test
  void shouldThrowNullPointer_whenIdIsNull() {
    assertThatNullPointerException()
        .isThrownBy(() -> new Room(null, List.of(), RoomStatus.WAITING_FOR_PLAYER))
        .withMessage("id");
  }

  @Test
  void shouldReject_whenIdIsBlank() {
    assertThatThrownBy(() -> new Room("   ", List.of(), RoomStatus.WAITING_FOR_PLAYER))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("blank");
  }

  @Test
  void shouldThrowNullPointer_whenPlayersIsNull() {
    assertThatNullPointerException()
        .isThrownBy(() -> new Room("r-1", null, RoomStatus.WAITING_FOR_PLAYER))
        .withMessage("players");
  }

  @Test
  void shouldThrowNullPointer_whenStatusIsNull() {
    assertThatNullPointerException()
        .isThrownBy(() -> new Room("r-1", List.of(), null))
        .withMessage("status");
  }

  @Test
  void shouldDefaultCreatorSideToWhite_whenUsingThreeArgConstructor() {
    Room room = new Room("r-1", List.of(ALICE), RoomStatus.WAITING_FOR_PLAYER);

    assertThat(room.creatorSide()).isEqualTo(Side.WHITE);
  }

  @Test
  void shouldStoreCreatorSide_whenUsingFourArgConstructor() {
    Room room = new Room("r-1", List.of(ALICE), RoomStatus.WAITING_FOR_PLAYER, Side.BLACK);

    assertThat(room.creatorSide()).isEqualTo(Side.BLACK);
  }

  @Test
  void shouldDefaultCreatorSideToWhite_whenCreatorSideIsNull() {
    Room room = new Room("r-1", List.of(ALICE), RoomStatus.WAITING_FOR_PLAYER, null);

    assertThat(room.creatorSide()).isEqualTo(Side.WHITE);
  }

  @Test
  void shouldReturnUnmodifiableView_fromPlayers() {
    Room room = new Room("r-1", List.of(ALICE), RoomStatus.WAITING_FOR_PLAYER);

    assertThatThrownBy(() -> room.players().add(BOB))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  // ---- Feature 22 (time-control): nullable timeControl + convenience constructors ----

  @Test
  void shouldDefaultTimeControlToNull_whenBuiltWithTheFourArgConstructor() {
    Room room = new Room("r-1", List.of(ALICE), RoomStatus.WAITING_FOR_PLAYER, Side.BLACK);

    assertThat(room.creatorSide()).isEqualTo(Side.BLACK);
    assertThat(room.timeControl()).isNull();
  }

  @Test
  void shouldDefaultTimeControlToNull_whenBuiltWithTheThreeArgConstructor() {
    Room room = new Room("r-1", List.of(ALICE), RoomStatus.WAITING_FOR_PLAYER);

    assertThat(room.timeControl()).isNull();
  }

  @Test
  void shouldStoreTimeControl_whenBuiltWithTheFiveArgConstructor() {
    TimeControl tc = new TimeControl(300_000L, 3_000L);
    Room room = new Room("r-1", List.of(ALICE), RoomStatus.WAITING_FOR_PLAYER, Side.WHITE, tc);

    assertThat(room.timeControl()).isEqualTo(tc);
  }

  @Test
  void shouldDefensivelyCopy_whenCallerMutatesInputList() {
    List<Player> mutable = new ArrayList<>();
    mutable.add(ALICE);

    Room room = new Room("r-1", mutable, RoomStatus.WAITING_FOR_PLAYER);
    mutable.add(BOB);

    assertThat(room.players()).containsExactly(ALICE);
  }
}
