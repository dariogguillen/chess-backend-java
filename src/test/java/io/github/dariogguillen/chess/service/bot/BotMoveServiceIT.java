package io.github.dariogguillen.chess.service.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.dariogguillen.chess.TestcontainersConfiguration;
import io.github.dariogguillen.chess.domain.Game;
import io.github.dariogguillen.chess.domain.GameStatus;
import io.github.dariogguillen.chess.domain.Move;
import io.github.dariogguillen.chess.domain.Player;
import io.github.dariogguillen.chess.domain.Square;
import io.github.dariogguillen.chess.persistence.GameHistoryRepository;
import io.github.dariogguillen.chess.service.GameStore;
import io.github.dariogguillen.chess.websocket.GameEngineFailedEvent;
import io.github.dariogguillen.chess.websocket.MoveEvent;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Integration tests for {@link BotMoveService} against Testcontainers Postgres + Redis with a
 * mocked {@link BotEngine} (so no Stockfish binary is required). The async bot move runs on the
 * real dedicated executor; Awaitility polls the {@link GameStore} for the result.
 *
 * <ol>
 *   <li>Happy path: with the bot to move, the scripted engine move is applied through {@code
 *       applyMove} and broadcast as an ordinary {@link MoveEvent}.
 *   <li>Engine failure: a throwing engine terminates the game as {@link GameStatus#ABANDONED},
 *       archives it, and broadcasts a {@link GameEngineFailedEvent} with the human as winner.
 *   <li>Not the bot's turn: a no-op (the human is to move) — the engine is never consulted.
 *   <li>Already-terminal: a terminal game is a no-op even though the bot nominally "owns" the side.
 *   <li>Strength threading (feature 23.5): the game's {@code botElo} reaches {@code
 *       BotEngine.chooseMove}; a game with a null {@code botElo} falls back to the configured
 *       default ({@code chess.bot.default-elo = 1500} in the test profile).
 * </ol>
 *
 * <p>{@link BotEngine} is replaced with a Mockito mock; {@link SimpMessagingTemplate} is a spy so
 * we can assert broadcasts without a STOMP client (the {@code BotGameIT} covers the end-to-end
 * wire).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class BotMoveServiceIT {

  private static final String STARTING_FEN =
      "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
  private static final Duration POLL_TIMEOUT = Duration.ofSeconds(5);

  @Autowired private BotMoveService botMoveService;
  @Autowired private GameStore gameStore;
  @Autowired private GameHistoryRepository repository;

  @MockitoBean private BotEngine botEngine;
  @MockitoSpyBean private SimpMessagingTemplate messagingTemplate;

  @BeforeEach
  void resetMocks() {
    reset(botEngine, messagingTemplate);
  }

  @Test
  void maybeTriggerBotMove_botToMove_appliesScriptedMoveAndBroadcasts() {
    // Bot is white and to move (empty move list); human is black.
    Game game = saveGame(Player.bot(), new Player(UUID.randomUUID(), "Alice"), GameStatus.ONGOING);
    when(botEngine.chooseMove(anyString(), anyInt())).thenReturn(move("e2", "e4"));

    botMoveService.maybeTriggerBotMove(game);

    await()
        .atMost(POLL_TIMEOUT)
        .untilAsserted(
            () -> {
              Game persisted = gameStore.findById(game.id()).orElseThrow();
              assertThat(persisted.moves()).hasSize(1);
              assertThat(persisted.moves().get(0).from().value()).isEqualTo("e2");
            });
    verify(messagingTemplate, timeout(POLL_TIMEOUT.toMillis()).times(1))
        .convertAndSend(eq("/topic/games/" + game.id()), any(MoveEvent.class));
  }

  @Test
  void maybeTriggerBotMove_engineThrows_abandonsArchivesAndBroadcastsEngineFailed() {
    Player human = new Player(UUID.randomUUID(), "Alice");
    Game game = saveGame(Player.bot(), human, GameStatus.ONGOING);
    when(botEngine.chooseMove(anyString(), anyInt()))
        .thenThrow(new BotEngineException("simulated engine timeout"));

    botMoveService.maybeTriggerBotMove(game);

    // failGame writes the ABANDONED status to Redis, then archives to Postgres, then broadcasts —
    // all on the async executor thread. Wait inside untilAsserted for BOTH the status flip AND the
    // archive so the post-await assertions never race the slower Postgres/broadcast steps.
    await()
        .atMost(POLL_TIMEOUT)
        .untilAsserted(
            () -> {
              Game persisted = gameStore.findById(game.id()).orElseThrow();
              assertThat(persisted.status()).isEqualTo(GameStatus.ABANDONED);
              assertThat(repository.findById(game.id())).isPresent();
            });
    verify(messagingTemplate, timeout(POLL_TIMEOUT.toMillis()).times(1))
        .convertAndSend(eq("/topic/games/" + game.id()), any(GameEngineFailedEvent.class));
    // No move was ever applied, so no MoveEvent was broadcast.
    verify(messagingTemplate, never()).convertAndSend(anyString(), any(MoveEvent.class));
  }

  @Test
  void maybeTriggerBotMove_humanToMove_isNoop() {
    // Human (white) is to move on an empty board; the bot is black.
    Game game = saveGame(new Player(UUID.randomUUID(), "Alice"), Player.bot(), GameStatus.ONGOING);

    botMoveService.maybeTriggerBotMove(game);

    // Give the executor a beat; the engine must never be consulted and nothing must be broadcast.
    await()
        .during(Duration.ofMillis(500))
        .atMost(POLL_TIMEOUT)
        .untilAsserted(
            () -> {
              Game persisted = gameStore.findById(game.id()).orElseThrow();
              assertThat(persisted.moves()).isEmpty();
              assertThat(persisted.status()).isEqualTo(GameStatus.ONGOING);
            });
    verify(botEngine, never()).chooseMove(anyString(), anyInt());
  }

  @Test
  void maybeTriggerBotMove_alreadyTerminalGame_isNoop() {
    Game game =
        saveGame(Player.bot(), new Player(UUID.randomUUID(), "Alice"), GameStatus.CHECKMATE);

    botMoveService.maybeTriggerBotMove(game);

    await()
        .during(Duration.ofMillis(500))
        .atMost(POLL_TIMEOUT)
        .untilAsserted(() -> assertThat(gameStore.findById(game.id())).isPresent());
    verify(botEngine, never()).chooseMove(anyString(), anyInt());
    verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
  }

  @Test
  void maybeTriggerBotMove_gameWithBotElo_passesThatEloToTheEngine() {
    // Bot is white and to move; the game carries an explicit botElo, which must reach the engine.
    Game game =
        saveGame(Player.bot(), new Player(UUID.randomUUID(), "Alice"), GameStatus.ONGOING, 2200);
    when(botEngine.chooseMove(anyString(), anyInt())).thenReturn(move("e2", "e4"));

    botMoveService.maybeTriggerBotMove(game);

    ArgumentCaptor<Integer> eloCaptor = ArgumentCaptor.forClass(Integer.class);
    await()
        .atMost(POLL_TIMEOUT)
        .untilAsserted(() -> verify(botEngine).chooseMove(anyString(), eloCaptor.capture()));
    assertThat(eloCaptor.getValue()).isEqualTo(2200);
  }

  @Test
  void maybeTriggerBotMove_gameWithNullBotElo_passesTheConfiguredDefaultToTheEngine() {
    // saveGame (8-arg) leaves botElo null; BotMoveService must fall back to chess.bot.default-elo.
    Game game = saveGame(Player.bot(), new Player(UUID.randomUUID(), "Alice"), GameStatus.ONGOING);
    when(botEngine.chooseMove(anyString(), anyInt())).thenReturn(move("e2", "e4"));

    botMoveService.maybeTriggerBotMove(game);

    ArgumentCaptor<Integer> eloCaptor = ArgumentCaptor.forClass(Integer.class);
    await()
        .atMost(POLL_TIMEOUT)
        .untilAsserted(() -> verify(botEngine).chooseMove(anyString(), eloCaptor.capture()));
    assertThat(eloCaptor.getValue()).isEqualTo(1500);
  }

  private Game saveGame(Player white, Player black, GameStatus status) {
    Game game =
        new Game(
            UUID.randomUUID(),
            "ROOM01",
            white,
            black,
            STARTING_FEN,
            STARTING_FEN,
            status,
            List.of());
    gameStore.save(game);
    return game;
  }

  private Game saveGame(Player white, Player black, GameStatus status, int botElo) {
    Game game =
        new Game(
            UUID.randomUUID(),
            "ROOM01",
            white,
            black,
            STARTING_FEN,
            STARTING_FEN,
            status,
            List.of(),
            null,
            null,
            null,
            null,
            botElo);
    gameStore.save(game);
    return game;
  }

  private static Move move(String from, String to) {
    return new Move(new Square(from), new Square(to), Optional.empty());
  }
}
