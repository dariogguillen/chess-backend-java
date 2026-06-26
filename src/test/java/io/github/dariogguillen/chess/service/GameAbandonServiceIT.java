package io.github.dariogguillen.chess.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.github.dariogguillen.chess.TestcontainersConfiguration;
import io.github.dariogguillen.chess.domain.Game;
import io.github.dariogguillen.chess.domain.GameResult;
import io.github.dariogguillen.chess.domain.GameStatus;
import io.github.dariogguillen.chess.domain.Player;
import io.github.dariogguillen.chess.persistence.GameEntity;
import io.github.dariogguillen.chess.persistence.GameHistoryRepository;
import io.github.dariogguillen.chess.websocket.GameAbandonedEvent;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Integration tests for {@link GameAbandonService} against Testcontainers Postgres + Redis. Covers
 * the three behaviours the disconnect-handling lifecycle relies on:
 *
 * <ol>
 *   <li>Happy path: an ongoing game is mutated to {@link GameStatus#ABANDONED}, archived to
 *       Postgres, and a {@link GameAbandonedEvent} is broadcast on {@code /topic/games/{gameId}}.
 *   <li>Already-terminal: a game that is already in a terminal status is left untouched — no second
 *       archive, no second broadcast.
 *   <li>Unknown game: an unknown UUID does not throw and produces no broadcast.
 * </ol>
 *
 * <p>The {@link SimpMessagingTemplate} is wrapped with {@link MockitoSpyBean} so we can assert what
 * was sent without standing up a STOMP client — the {@code DisconnectHandlingIT} suite covers the
 * end-to-end STOMP path. The spy is reset before each test so an invocation from one test cannot
 * leak into another's verification.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class GameAbandonServiceIT {

  private static final String STARTING_FEN =
      "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

  @Autowired private GameAbandonService gameAbandonService;
  @Autowired private GameStore gameStore;
  @Autowired private GameHistoryRepository repository;

  @MockitoSpyBean private SimpMessagingTemplate messagingTemplate;

  @BeforeEach
  void resetSpy() {
    reset(messagingTemplate);
  }

  @Test
  void abandon_nonTerminalGame_marksAbandoned_archives_broadcasts() {
    Game game = saveOngoingGame();
    UUID whiteId = game.white().id();

    gameAbandonService.abandon(game.id(), whiteId);

    Game persisted = gameStore.findById(game.id()).orElseThrow();
    assertThat(persisted.status()).isEqualTo(GameStatus.ABANDONED);
    assertThat(repository.findById(game.id())).isPresent();

    verify(messagingTemplate, times(1))
        .convertAndSend(eq("/topic/games/" + game.id()), any(GameAbandonedEvent.class));
  }

  @Test
  void abandon_whiteAbandons_blackWins_resultPersistedAndOnRedis() {
    Game game = saveOngoingGame();

    gameAbandonService.abandon(game.id(), game.white().id());

    // The non-abandoner (black) wins; the result must be on BOTH the Redis active copy and the
    // archived Postgres row (they must not disagree).
    Game persisted = gameStore.findById(game.id()).orElseThrow();
    assertThat(persisted.result()).isEqualTo(GameResult.BLACK_WIN);
    GameEntity archived = repository.findById(game.id()).orElseThrow();
    assertThat(archived.getResult()).isEqualTo(GameResult.BLACK_WIN);
  }

  @Test
  void abandon_blackAbandons_whiteWins_resultPersisted() {
    Game game = saveOngoingGame();

    gameAbandonService.abandon(game.id(), game.black().id());

    GameEntity archived = repository.findById(game.id()).orElseThrow();
    assertThat(archived.getResult()).isEqualTo(GameResult.WHITE_WIN);
  }

  @Test
  void abandon_alreadyTerminalGame_isNoop() {
    Game game = saveTerminalGame(GameStatus.CHECKMATE);
    long archiveCountBefore = repository.count();

    gameAbandonService.abandon(game.id(), game.white().id());

    Game persisted = gameStore.findById(game.id()).orElseThrow();
    assertThat(persisted.status()).isEqualTo(GameStatus.CHECKMATE);
    assertThat(repository.count()).isEqualTo(archiveCountBefore);
    verify(messagingTemplate, never()).convertAndSend(anyString(), any(GameAbandonedEvent.class));
  }

  @Test
  void abandon_unknownGame_isNoop() {
    UUID unknownGameId = UUID.randomUUID();

    gameAbandonService.abandon(unknownGameId, UUID.randomUUID());

    verify(messagingTemplate, never()).convertAndSend(anyString(), any(GameAbandonedEvent.class));
  }

  private Game saveOngoingGame() {
    Player white = new Player(UUID.randomUUID(), "Alice");
    Player black = new Player(UUID.randomUUID(), "Bob");
    Game game =
        new Game(
            UUID.randomUUID(),
            "ROOM01",
            white,
            black,
            STARTING_FEN,
            STARTING_FEN,
            GameStatus.ONGOING,
            List.of());
    gameStore.save(game);
    return game;
  }

  private Game saveTerminalGame(GameStatus status) {
    Player white = new Player(UUID.randomUUID(), "Alice");
    Player black = new Player(UUID.randomUUID(), "Bob");
    Game game =
        new Game(
            UUID.randomUUID(),
            "ROOM02",
            white,
            black,
            STARTING_FEN,
            STARTING_FEN,
            status,
            List.of());
    gameStore.save(game);
    return game;
  }
}
