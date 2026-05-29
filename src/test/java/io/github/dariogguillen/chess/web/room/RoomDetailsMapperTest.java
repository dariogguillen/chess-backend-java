package io.github.dariogguillen.chess.web.room;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dariogguillen.chess.domain.Player;
import io.github.dariogguillen.chess.domain.Room;
import io.github.dariogguillen.chess.domain.RoomStatus;
import io.github.dariogguillen.chess.domain.Side;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for the role-derivation rule inside {@link RoomDetailsMapper}. Role is computed
 * from {@link Room#creatorSide()}: the creator (index 0) holds that side, the joiner (index 1)
 * holds the opposite. These tests pin both phases of the lifecycle and both creator sides. The
 * mapper is pure, so it does not need a Spring context.
 */
class RoomDetailsMapperTest {

  private final RoomDetailsMapper mapper = new RoomDetailsMapper();

  @Test
  void derivesWhiteRoleForCreatorOnly() {
    Player creator = new Player(UUID.randomUUID(), "Alice");
    Room room = new Room("ABC123", List.of(creator), RoomStatus.WAITING_FOR_PLAYER);

    RoomDetailsResponse response = mapper.toResponse(room, Optional.empty());

    assertThat(response.roomId()).isEqualTo("ABC123");
    assertThat(response.status()).isEqualTo(RoomStatus.WAITING_FOR_PLAYER);
    assertThat(response.gameId()).isNull();
    assertThat(response.players()).hasSize(1);
    PlayerInRoom only = response.players().get(0);
    assertThat(only.id()).isEqualTo(creator.id());
    assertThat(only.displayName()).isEqualTo("Alice");
    assertThat(only.role()).isEqualTo("WHITE");
  }

  @Test
  void derivesWhiteBlackForBothPlayers() {
    Player creator = new Player(UUID.randomUUID(), "Alice");
    Player joiner = new Player(UUID.randomUUID(), "Bob");
    UUID gameId = UUID.randomUUID();
    Room room = new Room("ABC123", List.of(creator, joiner), RoomStatus.ACTIVE);

    RoomDetailsResponse response = mapper.toResponse(room, Optional.of(gameId));

    assertThat(response.roomId()).isEqualTo("ABC123");
    assertThat(response.status()).isEqualTo(RoomStatus.ACTIVE);
    assertThat(response.gameId()).isEqualTo(gameId);
    assertThat(response.players()).hasSize(2);
    PlayerInRoom white = response.players().get(0);
    PlayerInRoom black = response.players().get(1);
    assertThat(white.id()).isEqualTo(creator.id());
    assertThat(white.displayName()).isEqualTo("Alice");
    assertThat(white.role()).isEqualTo("WHITE");
    assertThat(black.id()).isEqualTo(joiner.id());
    assertThat(black.displayName()).isEqualTo("Bob");
    assertThat(black.role()).isEqualTo("BLACK");
  }

  @Test
  void derivesBlackForCreatorAndWhiteForJoiner_whenCreatorChoseBlack() {
    Player creator = new Player(UUID.randomUUID(), "Alice");
    Player joiner = new Player(UUID.randomUUID(), "Bob");
    Room room = new Room("ABC123", List.of(creator, joiner), RoomStatus.ACTIVE, Side.BLACK);

    RoomDetailsResponse response = mapper.toResponse(room, Optional.of(UUID.randomUUID()));

    assertThat(response.players()).hasSize(2);
    PlayerInRoom index0 = response.players().get(0);
    PlayerInRoom index1 = response.players().get(1);
    assertThat(index0.id()).isEqualTo(creator.id());
    assertThat(index0.role()).isEqualTo("BLACK");
    assertThat(index1.id()).isEqualTo(joiner.id());
    assertThat(index1.role()).isEqualTo("WHITE");
  }
}
