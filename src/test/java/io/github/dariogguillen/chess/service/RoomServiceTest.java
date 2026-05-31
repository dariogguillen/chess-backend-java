package io.github.dariogguillen.chess.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.dariogguillen.chess.config.BotProperties;
import io.github.dariogguillen.chess.domain.Game;
import io.github.dariogguillen.chess.domain.GameStatus;
import io.github.dariogguillen.chess.domain.OpponentKind;
import io.github.dariogguillen.chess.domain.Player;
import io.github.dariogguillen.chess.domain.Room;
import io.github.dariogguillen.chess.domain.RoomStatus;
import io.github.dariogguillen.chess.domain.Side;
import io.github.dariogguillen.chess.domain.SidePreference;
import io.github.dariogguillen.chess.domain.TimeControl;
import io.github.dariogguillen.chess.service.RoomService.CreatedRoom;
import io.github.dariogguillen.chess.service.RoomService.JoinedRoom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/**
 * Focused unit coverage for the side-resolution / white-black-assignment logic (feature 21,
 * `color-selection`) and the clock initialisation + first-flag scheduling (feature 22,
 * `time-control`). The integration tests pin the HTTP surface; this unit test pins the behaviours
 * an IT cannot give deterministically: that a {@link SidePreference#RANDOM} request maps to
 * whatever the stubbed {@link RandomSideChooser} returns, that a BLACK-creator join builds the
 * {@link Game} with white = joiner / black = creator, and that a timed join initialises the clock
 * and schedules white's flag while an untimed join does neither. {@link ChessRules} is real (pure);
 * the stores, the broadcaster, and {@link ClockTimerManager} are mocked. The {@link Clock} is fixed
 * so the clock-anchor instant is deterministic.
 */
@ExtendWith(MockitoExtension.class)
class RoomServiceTest {

  private static final Instant NOW = Instant.parse("2026-05-29T10:00:00Z");
  private static final int DEFAULT_ELO = 1500;

  @Mock private RoomStore roomStore;
  @Mock private GameStore gameStore;
  @Mock private RoomCodeGenerator codeGenerator;
  @Mock private SimpMessagingTemplate messagingTemplate;
  @Mock private RandomSideChooser randomSideChooser;
  @Mock private ClockTimerManager clockTimerManager;

  private RoomService roomService;

  @BeforeEach
  void setUp() {
    Clock fixedClock = Clock.fixed(NOW, ZoneOffset.UTC);
    BotProperties botProperties =
        new BotProperties(
            "/usr/games/stockfish", Duration.ofMillis(500), Duration.ofSeconds(5), 2, DEFAULT_ELO);
    roomService =
        new RoomService(
            roomStore,
            gameStore,
            codeGenerator,
            new ChessRules(),
            messagingTemplate,
            randomSideChooser,
            fixedClock,
            clockTimerManager,
            botProperties);
  }

  @Test
  void createRoom_randomPreference_storesTheSideTheChooserReturns() {
    when(codeGenerator.generate()).thenReturn("ABC234");
    when(randomSideChooser.choose()).thenReturn(Side.BLACK);
    when(roomStore.compute(anyString(), any())).thenAnswer(applyOnExisting(null));

    CreatedRoom created =
        roomService.createRoom("Alice", SidePreference.RANDOM, null, null, null, null);

    assertThat(created.room().creatorSide()).isEqualTo(Side.BLACK);
  }

  @Test
  void createRoom_withTimeControl_storesItOnTheRoom() {
    TimeControl tc = new TimeControl(300_000L, 3_000L);
    when(codeGenerator.generate()).thenReturn("ABC234");
    when(roomStore.compute(anyString(), any())).thenAnswer(applyOnExisting(null));

    CreatedRoom created =
        roomService.createRoom("Alice", SidePreference.WHITE, null, tc, null, null);

    assertThat(created.room().timeControl()).isEqualTo(tc);
  }

  @Test
  void createRoom_botOpponent_buildsActiveTwoSideGameImmediately() {
    when(codeGenerator.generate()).thenReturn("ABC234");
    when(roomStore.compute(anyString(), any())).thenAnswer(applyOnExisting(null));

    CreatedRoom created =
        roomService.createRoom("Alice", SidePreference.WHITE, null, null, OpponentKind.BOT, null);

    // The room is full and active, the game exists immediately, and the bot is the opposite side.
    assertThat(created.room().status()).isEqualTo(RoomStatus.ACTIVE);
    assertThat(created.room().players()).hasSize(2);
    assertThat(created.room().joinToken()).isNull();
    assertThat(created.game()).isNotNull();
    assertThat(created.game().white().id()).isEqualTo(created.creator().id());
    assertThat(created.game().black().isBot()).isTrue();
    assertThat(created.game().status()).isEqualTo(GameStatus.ONGOING);
    verify(gameStore).save(created.game());
  }

  @Test
  void createRoom_botOpponentNoElo_storesTheConfiguredDefaultOnTheGame() {
    when(codeGenerator.generate()).thenReturn("ABC234");
    when(roomStore.compute(anyString(), any())).thenAnswer(applyOnExisting(null));

    // A BOT room created with a null botElo falls back to BotProperties.defaultElo.
    CreatedRoom created =
        roomService.createRoom("Alice", SidePreference.WHITE, null, null, OpponentKind.BOT, null);

    assertThat(created.game().botElo()).isEqualTo(DEFAULT_ELO);
  }

  @Test
  void createRoom_botOpponentWithElo_storesTheRequestedEloOnTheGame() {
    when(codeGenerator.generate()).thenReturn("ABC234");
    when(roomStore.compute(anyString(), any())).thenAnswer(applyOnExisting(null));

    CreatedRoom created =
        roomService.createRoom("Alice", SidePreference.WHITE, null, null, OpponentKind.BOT, 2200);

    assertThat(created.game().botElo()).isEqualTo(2200);
  }

  @Test
  void createRoom_friendOpponent_leavesBotEloNullOnNoGame() {
    when(codeGenerator.generate()).thenReturn("ABC234");
    when(roomStore.compute(anyString(), any())).thenAnswer(applyOnExisting(null));

    // A FRIEND room has no game yet, and a stray botElo is ignored (no bot to apply it to).
    CreatedRoom created =
        roomService.createRoom(
            "Alice", SidePreference.WHITE, null, null, OpponentKind.FRIEND, 2200);

    assertThat(created.game()).isNull();
  }

  @Test
  void createRoom_botOpponentBlackCreator_putsBotOnWhite() {
    when(codeGenerator.generate()).thenReturn("ABC234");
    when(roomStore.compute(anyString(), any())).thenAnswer(applyOnExisting(null));

    CreatedRoom created =
        roomService.createRoom("Alice", SidePreference.BLACK, null, null, OpponentKind.BOT, null);

    assertThat(created.game().white().isBot()).isTrue();
    assertThat(created.game().black().id()).isEqualTo(created.creator().id());
  }

  @Test
  void joinRoom_blackCreator_assignsJoinerWhiteAndCreatorBlack() {
    Player creator = new Player(UUID.randomUUID(), "Alice");
    Room waiting = new Room("ABC234", List.of(creator), RoomStatus.WAITING_FOR_PLAYER, Side.BLACK);
    when(roomStore.compute(anyString(), any())).thenAnswer(applyOnExisting(waiting));

    JoinedRoom joined = roomService.joinRoom("ABC234", "Bob", null, null);

    assertThat(joined.room().creatorSide()).isEqualTo(Side.BLACK);
    assertThat(joined.game().white().id()).isEqualTo(joined.joiner().id());
    assertThat(joined.game().black().id()).isEqualTo(creator.id());
  }

  @Test
  void joinRoom_legacyRoomWithNullToken_acceptsTokenLessJoin() {
    // Deploy-safety hinge (feature 22.7): a room serialised into Redis before this deploy
    // deserialises with joinToken == null. The new REST API always stamps a token, so this legacy
    // shape cannot be produced through the controller — it is proven here at the service layer.
    // Built via the 4-arg convenience constructor, so joinToken defaults to null.
    Player creator = new Player(UUID.randomUUID(), "Alice");
    Room legacy = new Room("ABC234", List.of(creator), RoomStatus.WAITING_FOR_PLAYER, Side.WHITE);
    when(roomStore.compute(anyString(), any())).thenAnswer(applyOnExisting(legacy));

    JoinedRoom joined = roomService.joinRoom("ABC234", "Bob", null, null);

    assertThat(joined.room().status()).isEqualTo(RoomStatus.ACTIVE);
    assertThat(joined.room().players()).hasSize(2);
    assertThat(joined.game().black().id()).isEqualTo(joined.joiner().id());
  }

  @Test
  void joinRoom_timedRoom_initialisesClockAndSchedulesWhiteFlag() {
    Player creator = new Player(UUID.randomUUID(), "Alice");
    TimeControl tc = new TimeControl(300_000L, 3_000L);
    Room waiting =
        new Room("ABC234", List.of(creator), RoomStatus.WAITING_FOR_PLAYER, Side.WHITE, tc);
    when(roomStore.compute(anyString(), any())).thenAnswer(applyOnExisting(waiting));

    JoinedRoom joined = roomService.joinRoom("ABC234", "Bob", null, null);

    Game game = joined.game();
    assertThat(game.isTimed()).isTrue();
    assertThat(game.whiteTimeRemainingMs()).isEqualTo(300_000L);
    assertThat(game.blackTimeRemainingMs()).isEqualTo(300_000L);
    assertThat(game.incrementMs()).isEqualTo(3_000L);
    assertThat(game.lastMoveAt()).isEqualTo(NOW);
    // White's first flag fires at the creation anchor + white's remaining time.
    verify(clockTimerManager).scheduleFlag(eq(game.id()), eq(NOW.plusMillis(300_000L)));
  }

  @Test
  void joinRoom_untimedRoom_leavesClockNullAndSchedulesNothing() {
    Player creator = new Player(UUID.randomUUID(), "Alice");
    Room waiting = new Room("ABC234", List.of(creator), RoomStatus.WAITING_FOR_PLAYER, Side.WHITE);
    when(roomStore.compute(anyString(), any())).thenAnswer(applyOnExisting(waiting));

    JoinedRoom joined = roomService.joinRoom("ABC234", "Bob", null, null);

    Game game = joined.game();
    assertThat(game.isTimed()).isFalse();
    assertThat(game.whiteTimeRemainingMs()).isNull();
    assertThat(game.blackTimeRemainingMs()).isNull();
    assertThat(game.lastMoveAt()).isNull();
    assertThat(game.incrementMs()).isNull();
    verify(clockTimerManager, never()).scheduleFlag(any(), any());
  }

  /**
   * Stubs {@code compute(key, fn)} to invoke the remapping function with {@code existing} as the
   * current value and return its result — the same pattern the previous tests used inline.
   */
  private static Answer<Room> applyOnExisting(Room existing) {
    return invocation -> {
      BiFunction<String, Room, Room> fn = invocation.getArgument(1);
      return fn.apply(invocation.getArgument(0), existing);
    };
  }
}
