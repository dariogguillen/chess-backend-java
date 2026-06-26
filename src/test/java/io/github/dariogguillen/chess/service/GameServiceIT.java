package io.github.dariogguillen.chess.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dariogguillen.chess.TestcontainersConfiguration;
import io.github.dariogguillen.chess.domain.Game;
import io.github.dariogguillen.chess.domain.GameResult;
import io.github.dariogguillen.chess.domain.GameStatus;
import io.github.dariogguillen.chess.domain.Move;
import io.github.dariogguillen.chess.domain.Player;
import io.github.dariogguillen.chess.domain.Square;
import io.github.dariogguillen.chess.persistence.GameEntity;
import io.github.dariogguillen.chess.persistence.GameHistoryRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Integration tests for {@link GameService}'s terminal-by-move result derivation (feature 23.92,
 * {@code game-result-persistence}). The checkmate-per-side cases are covered end-to-end through the
 * REST layer in {@code GameControllerIT} (Fool's Mate -> BLACK_WIN, Scholar's Mate -> WHITE_WIN);
 * this IT covers the <em>draw</em> branch, which cannot be reached from the standard starting
 * position the REST create path uses without a long forced line. We seed a game in {@link
 * GameStore} directly from a constructed stalemate position and drive the single
 * mating-into-stalemate move through {@link GameService#applyMove(UUID, UUID, Move)}, then assert
 * the persisted {@code result} is {@link GameResult#DRAW} on both the Redis active copy and the
 * archived Postgres row.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class GameServiceIT {

  // Black king on a8 (its only piece), white queen on b6, white king on h1, white to move. White
  // plays Qb6-c7 — the black king is not in check but has no legal move: stalemate. The same
  // position ChessRulesTest uses for its stalemate unit case.
  private static final String STALEMATE_SETUP_FEN = "k7/8/1Q6/8/8/8/8/7K w - - 0 1";

  @Autowired private GameService gameService;
  @Autowired private GameStore gameStore;
  @Autowired private GameHistoryRepository repository;

  @Test
  void applyMove_intoStalemate_archivesDrawResult() {
    Player white = new Player(UUID.randomUUID(), "Alice");
    Player black = new Player(UUID.randomUUID(), "Bob");
    Game game =
        new Game(
            UUID.randomUUID(),
            "ROOM01",
            white,
            black,
            STALEMATE_SETUP_FEN,
            STALEMATE_SETUP_FEN,
            GameStatus.ONGOING,
            List.of());
    gameStore.save(game);

    // White (to move on the empty move list) plays Qb6-c7, stalemating black.
    Game updated =
        gameService.applyMove(
            game.id(), white.id(), new Move(new Square("b6"), new Square("c7"), Optional.empty()));

    assertThat(updated.status()).isEqualTo(GameStatus.STALEMATE);
    // A stalemate is a draw — no winner. The result must agree on Redis and in the archive.
    assertThat(updated.result()).isEqualTo(GameResult.DRAW);
    GameEntity archived = repository.findById(game.id()).orElseThrow();
    assertThat(archived.getStatus()).isEqualTo(GameStatus.STALEMATE);
    assertThat(archived.getResult()).isEqualTo(GameResult.DRAW);
  }
}
