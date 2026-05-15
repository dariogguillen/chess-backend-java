package io.github.dariogguillen.chess.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RoomTest {

  private static final Player ALICE = new Player("alice", "Alice");
  private static final Player BOB = new Player("bob", "Bob");
  private static final Player CHARLIE = new Player("charlie", "Charlie");

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
    Player aliceClone = new Player("alice", "Alice the second");

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
  void shouldReturnUnmodifiableView_fromPlayers() {
    Room room = new Room("r-1", List.of(ALICE), RoomStatus.WAITING_FOR_PLAYER);

    assertThatThrownBy(() -> room.players().add(BOB))
        .isInstanceOf(UnsupportedOperationException.class);
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
