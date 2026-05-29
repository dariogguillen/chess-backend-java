package io.github.dariogguillen.chess.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.github.dariogguillen.chess.domain.Game;
import io.github.dariogguillen.chess.domain.Player;
import io.github.dariogguillen.chess.domain.Room;
import io.github.dariogguillen.chess.domain.RoomStatus;
import io.github.dariogguillen.chess.domain.Side;
import io.github.dariogguillen.chess.domain.SidePreference;
import io.github.dariogguillen.chess.service.RoomService.CreatedRoom;
import io.github.dariogguillen.chess.service.RoomService.JoinedRoom;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/**
 * Focused unit coverage for the side-resolution and white/black-assignment logic added by feature
 * 21 (`color-selection`). The integration tests pin the HTTP surface; this unit test pins the two
 * behaviours an IT cannot give deterministically: that a {@link SidePreference#RANDOM} request maps
 * to whatever the (stubbed) {@link RandomSideChooser} returns, and that a BLACK-creator join builds
 * the {@link Game} with white = joiner / black = creator. {@link ChessRules} is real (it is pure);
 * the stores and the broadcaster are mocked.
 */
@ExtendWith(MockitoExtension.class)
class RoomServiceTest {

  @Mock private RoomStore roomStore;
  @Mock private GameStore gameStore;
  @Mock private RoomCodeGenerator codeGenerator;
  @Mock private SimpMessagingTemplate messagingTemplate;
  @Mock private RandomSideChooser randomSideChooser;

  private RoomService roomService;

  @BeforeEach
  void setUp() {
    roomService =
        new RoomService(
            roomStore,
            gameStore,
            codeGenerator,
            new ChessRules(),
            messagingTemplate,
            randomSideChooser);
  }

  @Test
  void createRoom_randomPreference_storesTheSideTheChooserReturns() {
    when(codeGenerator.generate()).thenReturn("ABC234");
    when(randomSideChooser.choose()).thenReturn(Side.BLACK);
    // compute on an absent key: pass null as the existing value and return what the lambda builds.
    when(roomStore.compute(anyString(), any()))
        .thenAnswer(
            invocation -> {
              BiFunction<String, Room, Room> fn = invocation.getArgument(1);
              return fn.apply(invocation.getArgument(0), null);
            });

    CreatedRoom created = roomService.createRoom("Alice", SidePreference.RANDOM, null);

    assertThat(created.room().creatorSide()).isEqualTo(Side.BLACK);
  }

  @Test
  void joinRoom_blackCreator_assignsJoinerWhiteAndCreatorBlack() {
    Player creator = new Player(UUID.randomUUID(), "Alice");
    Room waiting = new Room("ABC234", List.of(creator), RoomStatus.WAITING_FOR_PLAYER, Side.BLACK);
    // compute on the existing waiting room: feed it to the lambda and return the result.
    when(roomStore.compute(anyString(), any()))
        .thenAnswer(
            invocation -> {
              BiFunction<String, Room, Room> fn = invocation.getArgument(1);
              return fn.apply(invocation.getArgument(0), waiting);
            });

    JoinedRoom joined = roomService.joinRoom("ABC234", "Bob", null);

    assertThat(joined.room().creatorSide()).isEqualTo(Side.BLACK);
    assertThat(joined.game().white().id()).isEqualTo(joined.joiner().id());
    assertThat(joined.game().black().id()).isEqualTo(creator.id());
  }
}
