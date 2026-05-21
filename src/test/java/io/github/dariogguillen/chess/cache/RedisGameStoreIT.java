package io.github.dariogguillen.chess.cache;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dariogguillen.chess.TestcontainersConfiguration;
import io.github.dariogguillen.chess.domain.Game;
import io.github.dariogguillen.chess.domain.GameStatus;
import io.github.dariogguillen.chess.domain.Move;
import io.github.dariogguillen.chess.domain.Piece;
import io.github.dariogguillen.chess.domain.Player;
import io.github.dariogguillen.chess.domain.Square;
import io.github.dariogguillen.chess.service.GameStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Integration tests for {@link RedisGameStore} against a real Redis. Mirrors {@code
 * RedisRoomStoreIT} for the {@code game:{id}} keyspace and additionally asserts the move-history
 * list (including a move with a promotion {@link Optional}) round-trips intact through the JSON
 * serializer.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class RedisGameStoreIT {

  private static final String STARTING_FEN =
      "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

  @Autowired private GameStore gameStore;

  @Test
  void save_thenFindById_returnsEqualGame_includingMoveHistory() {
    Player white = new Player(UUID.randomUUID().toString(), "Alice");
    Player black = new Player(UUID.randomUUID().toString(), "Bob");
    Move nonPromotion = new Move(new Square("e2"), new Square("e4"), Optional.<Piece>empty());
    Move withPromotion = new Move(new Square("a7"), new Square("a8"), Optional.of(Piece.QUEEN));
    Game game =
        new Game(
            UUID.randomUUID().toString(),
            "ROOM01",
            white,
            black,
            STARTING_FEN,
            STARTING_FEN,
            GameStatus.ONGOING,
            List.of(nonPromotion, withPromotion));

    gameStore.save(game);

    Optional<Game> found = gameStore.findById(game.id());
    assertThat(found).isPresent();
    Game loaded = found.orElseThrow();
    assertThat(loaded.id()).isEqualTo(game.id());
    assertThat(loaded.roomId()).isEqualTo("ROOM01");
    assertThat(loaded.white()).isEqualTo(white);
    assertThat(loaded.black()).isEqualTo(black);
    assertThat(loaded.status()).isEqualTo(GameStatus.ONGOING);
    assertThat(loaded.startingFen()).isEqualTo(STARTING_FEN);
    assertThat(loaded.fen()).isEqualTo(STARTING_FEN);
    assertThat(loaded.moves()).containsExactly(nonPromotion, withPromotion);
    // Sanity: the Optional<Piece> serializes as either the inner value or null, not as a
    // serialized Optional wrapper. The second move's promotion must round-trip.
    assertThat(loaded.moves().get(1).promotion()).contains(Piece.QUEEN);
    assertThat(loaded.moves().get(0).promotion()).isEmpty();
  }

  @Test
  void findById_unknownId_returnsEmpty() {
    assertThat(gameStore.findById(UUID.randomUUID().toString())).isEmpty();
  }

  @Test
  void compute_onMissingKey_passesNullAndPersistsResult() {
    String id = UUID.randomUUID().toString();
    Player white = new Player(UUID.randomUUID().toString(), "Alice");
    Player black = new Player(UUID.randomUUID().toString(), "Bob");

    Game created =
        gameStore.compute(
            id,
            (key, existing) -> {
              assertThat(existing).isNull();
              return new Game(
                  key,
                  "ROOM02",
                  white,
                  black,
                  STARTING_FEN,
                  STARTING_FEN,
                  GameStatus.ONGOING,
                  List.of());
            });

    assertThat(created.id()).isEqualTo(id);
    assertThat(gameStore.findById(id)).contains(created);
  }

  @Test
  void compute_returningNull_removesKey() {
    Player white = new Player(UUID.randomUUID().toString(), "Alice");
    Player black = new Player(UUID.randomUUID().toString(), "Bob");
    Game game =
        new Game(
            UUID.randomUUID().toString(),
            "ROOM03",
            white,
            black,
            STARTING_FEN,
            STARTING_FEN,
            GameStatus.ONGOING,
            List.of());
    gameStore.save(game);

    Game result = gameStore.compute(game.id(), (key, existing) -> null);

    assertThat(result).isNull();
    assertThat(gameStore.findById(game.id())).isEmpty();
  }

  @Test
  void compute_serializesConcurrentCallsOnSameKey() throws Exception {
    // Simulate the canonical use of compute: read game, append a move, write game. With true
    // atomicity, N concurrent compute calls produce a final move list of exactly N moves. Without
    // it, lost updates would leave fewer.
    Player white = new Player(UUID.randomUUID().toString(), "Alice");
    Player black = new Player(UUID.randomUUID().toString(), "Bob");
    Game initial =
        new Game(
            UUID.randomUUID().toString(),
            "ROOM04",
            white,
            black,
            STARTING_FEN,
            STARTING_FEN,
            GameStatus.ONGOING,
            List.of());
    gameStore.save(initial);

    int threads = 8;
    ExecutorService executor = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(threads);
    AtomicInteger failures = new AtomicInteger();

    try {
      for (int i = 0; i < threads; i++) {
        executor.submit(
            () -> {
              try {
                start.await();
                gameStore.compute(
                    initial.id(),
                    (key, existing) -> {
                      if (existing == null) {
                        throw new IllegalStateException("game disappeared");
                      }
                      List<Move> updatedMoves = new ArrayList<>(existing.moves());
                      // A move that is structurally valid (from != to) but ignored by the
                      // domain — chesslib is not involved here, the store only cares about
                      // serialization round-trips.
                      updatedMoves.add(
                          new Move(new Square("e2"), new Square("e4"), Optional.<Piece>empty()));
                      return new Game(
                          existing.id(),
                          existing.roomId(),
                          existing.white(),
                          existing.black(),
                          existing.startingFen(),
                          existing.fen(),
                          existing.status(),
                          updatedMoves);
                    });
              } catch (RuntimeException e) {
                failures.incrementAndGet();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } finally {
                done.countDown();
              }
            });
      }
      start.countDown();
      assertThat(done.await(15, TimeUnit.SECONDS)).isTrue();
    } finally {
      executor.shutdownNow();
    }

    assertThat(failures.get()).isZero();
    Game finalGame = gameStore.findById(initial.id()).orElseThrow();
    assertThat(finalGame.moves()).hasSize(threads);
  }
}
