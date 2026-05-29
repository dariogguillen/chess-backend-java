package io.github.dariogguillen.chess.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.dariogguillen.chess.domain.Game;
import io.github.dariogguillen.chess.domain.GameStatus;
import io.github.dariogguillen.chess.domain.Move;
import io.github.dariogguillen.chess.domain.Player;
import io.github.dariogguillen.chess.domain.Square;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/**
 * Focused unit coverage for the clock arithmetic in {@link GameService#applyMove} (feature 22,
 * `time-control`). A <strong>fixed {@link Clock}</strong> makes the decrement / clamp / increment /
 * anchor maths deterministic to the millisecond — the value an integration test cannot give cheaply
 * (it would have to sleep wall-clock and then assert on a fuzzy remaining time). {@link GameStore}
 * is mocked to feed a hand-built game into the {@code compute} lambda; {@link ChessRules} is real
 * (pure); the broadcaster, the history service, and {@link ClockTimerManager} are mocked.
 *
 * <p>The arithmetic under test, for the side-to-move S: {@code elapsed = now - lastMoveAt}; {@code
 * remaining[S] = max(0, remaining[S] - elapsed) + incrementMs}; {@code lastMoveAt = now}.
 */
@ExtendWith(MockitoExtension.class)
class GameServiceClockTest {

  private static final Instant NOW = Instant.parse("2026-05-29T10:00:10Z");
  private static final UUID WHITE_ID = UUID.fromString("00000000-0000-0000-0000-00000000000a");
  private static final UUID BLACK_ID = UUID.fromString("00000000-0000-0000-0000-00000000000b");
  private static final UUID GAME_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final Player WHITE = new Player(WHITE_ID, "Alice");
  private static final Player BLACK = new Player(BLACK_ID, "Bob");
  private static final Move E2E4 = new Move(new Square("e2"), new Square("e4"), Optional.empty());

  @Mock private GameStore gameStore;
  @Mock private SimpMessagingTemplate messagingTemplate;
  @Mock private GameHistoryService gameHistoryService;
  @Mock private ClockTimerManager clockTimerManager;

  private GameService gameService;
  private final ChessRules chessRules = new ChessRules();
  private String initialFen;

  @BeforeEach
  void setUp() {
    Clock fixedClock = Clock.fixed(NOW, ZoneOffset.UTC);
    gameService =
        new GameService(
            gameStore,
            chessRules,
            messagingTemplate,
            fixedClock,
            gameHistoryService,
            clockTimerManager);
    initialFen = chessRules.standardInitialState().currentFen();
  }

  @Test
  void applyMove_timedGame_decrementsMoverClampedAndAddsIncrement_andRearmsBlackFlag() {
    // White's clock anchor is 5s before NOW: white "spent" 5s thinking about this move.
    Instant anchor = NOW.minusMillis(5_000L);
    Game timed =
        new Game(
            GAME_ID,
            "ABC234",
            WHITE,
            BLACK,
            initialFen,
            initialFen,
            GameStatus.ONGOING,
            List.of(),
            120_000L,
            120_000L,
            anchor,
            2_000L);
    stubComputeWith(timed);

    Game updated = gameService.applyMove(GAME_ID, WHITE_ID, E2E4);

    // White spent 5s; decrement 120000 - 5000 = 115000, then + 2000 increment = 117000.
    assertThat(updated.whiteTimeRemainingMs()).isEqualTo(117_000L);
    // Black untouched.
    assertThat(updated.blackTimeRemainingMs()).isEqualTo(120_000L);
    // Anchor moves to NOW for black's turn.
    assertThat(updated.lastMoveAt()).isEqualTo(NOW);
    assertThat(updated.incrementMs()).isEqualTo(2_000L);
    // Black is now to move; the flag is rearmed at NOW + black's remaining (120000ms).
    verify(clockTimerManager).scheduleFlag(eq(GAME_ID), eq(NOW.plusMillis(120_000L)));
  }

  @Test
  void applyMove_timedGame_clampsMoverAtZero_whenElapsedExceedsRemaining() {
    // White had only 3s left but spent 5s — the decrement clamps at 0 before the increment.
    Instant anchor = NOW.minusMillis(5_000L);
    Game timed =
        new Game(
            GAME_ID,
            "ABC234",
            WHITE,
            BLACK,
            initialFen,
            initialFen,
            GameStatus.ONGOING,
            List.of(),
            3_000L,
            120_000L,
            anchor,
            2_000L);
    stubComputeWith(timed);

    Game updated = gameService.applyMove(GAME_ID, WHITE_ID, E2E4);

    // max(0, 3000 - 5000) = 0, then + 2000 increment = 2000.
    assertThat(updated.whiteTimeRemainingMs()).isEqualTo(2_000L);
  }

  @Test
  void applyMove_untimedGame_leavesClockNull_andNeverSchedulesFlag() {
    Game untimed =
        new Game(
            GAME_ID, "ABC234", WHITE, BLACK, initialFen, initialFen, GameStatus.ONGOING, List.of());
    stubComputeWith(untimed);

    Game updated = gameService.applyMove(GAME_ID, WHITE_ID, E2E4);

    assertThat(updated.isTimed()).isFalse();
    assertThat(updated.whiteTimeRemainingMs()).isNull();
    assertThat(updated.lastMoveAt()).isNull();
    verify(clockTimerManager, never()).scheduleFlag(any(), any());
  }

  private void stubComputeWith(Game existing) {
    when(gameStore.compute(eq(GAME_ID), any()))
        .thenAnswer(
            invocation -> {
              BiFunction<UUID, Game, Game> fn = invocation.getArgument(1);
              return fn.apply(GAME_ID, existing);
            });
  }
}
