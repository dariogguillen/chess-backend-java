package io.github.dariogguillen.chess.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dariogguillen.chess.TestcontainersConfiguration;
import io.github.dariogguillen.chess.domain.GameResult;
import io.github.dariogguillen.chess.domain.GameStatus;
import io.github.dariogguillen.chess.service.GameHistoryService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Limit;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration tests for {@link GameHistoryRepository} against the Testcontainers Postgres. Asserts
 * that a {@link GameEntity} with attached {@link MoveEntity} children persists in one save (cascade
 * works) and that {@link GameHistoryRepository#findByPlayerId(UUID, Limit)} returns the right
 * projection for both white and black sides, ordered newest first.
 *
 * <p>The {@link Transactional} on {@link #save_thenFindById_returnsEntityWithMoves()} keeps the
 * session open across the round-trip so the lazy {@code moves} collection is reachable for the
 * assertion. That mirrors how the {@link GameHistoryService} scopes its own reads — outside a
 * transaction (with {@code spring.jpa.open-in-view: false}) the collection would be unreachable.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class GameHistoryRepositoryIT {

  private static final String STARTING_FEN =
      "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
  private static final String FINAL_FEN =
      "rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq - 1 3";

  @Autowired private GameHistoryRepository repository;

  @Test
  @Transactional
  void save_thenFindById_returnsEntityWithMoves() {
    UUID gameId = UUID.randomUUID();
    UUID whiteId = UUID.randomUUID();
    UUID blackId = UUID.randomUUID();
    GameEntity entity = newGameEntity(gameId, whiteId, blackId, "Alice", "Bob");
    entity.setMoves(
        List.of(
            new MoveEntity(entity, 0, "e2", "e4", null),
            new MoveEntity(entity, 1, "a7", "a8", "QUEEN")));

    repository.save(entity);
    repository.flush();

    GameEntity loaded = repository.findById(gameId).orElseThrow();
    assertThat(loaded.getRoomId()).isEqualTo("ROOM01");
    assertThat(loaded.getWhitePlayerId()).isEqualTo(whiteId);
    assertThat(loaded.getBlackPlayerId()).isEqualTo(blackId);
    assertThat(loaded.getStatus()).isEqualTo(GameStatus.CHECKMATE);
    assertThat(loaded.getStartingFen()).isEqualTo(STARTING_FEN);
    assertThat(loaded.getFinalFen()).isEqualTo(FINAL_FEN);
    assertThat(loaded.getMoves()).hasSize(2);

    MoveEntity firstMove = loaded.getMoves().get(0);
    assertThat(firstMove.getMoveIdx()).isZero();
    assertThat(firstMove.getFromSquare()).isEqualTo("e2");
    assertThat(firstMove.getToSquare()).isEqualTo("e4");
    assertThat(firstMove.getPromotion()).isNull();

    MoveEntity secondMove = loaded.getMoves().get(1);
    assertThat(secondMove.getMoveIdx()).isEqualTo(1);
    assertThat(secondMove.getFromSquare()).isEqualTo("a7");
    assertThat(secondMove.getToSquare()).isEqualTo("a8");
    assertThat(secondMove.getPromotion()).isEqualTo("QUEEN");
  }

  @Test
  void findByPlayerId_findsGamesWherePlayerIsWhiteOrBlack_newestFirst() {
    UUID alice = UUID.randomUUID();
    UUID bob = UUID.randomUUID();
    UUID carol = UUID.randomUUID();

    // Three games, three timestamps, with Alice on different sides per game.
    Instant t0 = Instant.parse("2026-05-19T10:00:00Z");
    Instant t1 = Instant.parse("2026-05-20T10:00:00Z");
    Instant t2 = Instant.parse("2026-05-21T10:00:00Z");

    GameEntity game1 = newGameEntity(UUID.randomUUID(), alice, bob, "Alice", "Bob");
    game1.setEndedAt(t0);
    GameEntity game2 = newGameEntity(UUID.randomUUID(), carol, alice, "Carol", "Alice");
    game2.setEndedAt(t1);
    GameEntity game3 = newGameEntity(UUID.randomUUID(), alice, carol, "Alice", "Carol");
    game3.setEndedAt(t2);
    // A control game with neither player matching Alice — must not appear in her history.
    GameEntity control = newGameEntity(UUID.randomUUID(), bob, carol, "Bob", "Carol");
    control.setEndedAt(t2);

    repository.saveAll(List.of(game1, game2, game3, control));
    repository.flush();

    List<ArchivedGamePlayerView> views = repository.findByPlayerId(alice, Limit.of(50));

    assertThat(views).hasSize(3);
    // Newest first.
    assertThat(views.get(0).id()).isEqualTo(game3.getId());
    assertThat(views.get(1).id()).isEqualTo(game2.getId());
    assertThat(views.get(2).id()).isEqualTo(game1.getId());
    assertThat(views.get(0).endedAt()).isEqualTo(t2);
    assertThat(views.get(2).endedAt()).isEqualTo(t0);
    // The control game is excluded.
    assertThat(views).noneMatch(v -> v.id().equals(control.getId()));
  }

  @Test
  void findByPlayerId_unknownPlayer_returnsEmptyList() {
    List<ArchivedGamePlayerView> views = repository.findByPlayerId(UUID.randomUUID(), Limit.of(50));
    assertThat(views).isEmpty();
  }

  @Test
  void findByPlayerId_limitCapsResultCount() {
    UUID solo = UUID.randomUUID();
    Instant base = Instant.parse("2026-05-21T10:00:00Z");
    for (int i = 0; i < 5; i++) {
      GameEntity g = newGameEntity(UUID.randomUUID(), solo, UUID.randomUUID(), "Solo", "Opp" + i);
      g.setEndedAt(base.plusSeconds(i));
      repository.save(g);
    }
    repository.flush();

    List<ArchivedGamePlayerView> views = repository.findByPlayerId(solo, Limit.of(3));
    assertThat(views).hasSize(3);
  }

  @Test
  void findByPlayerId_carriesResultInProjection() {
    UUID player = UUID.randomUUID();
    GameEntity game =
        newGameEntity(UUID.randomUUID(), player, UUID.randomUUID(), "Winner", "Loser");
    game.setResult(GameResult.WHITE_WIN);
    repository.save(game);
    repository.flush();

    List<ArchivedGamePlayerView> views = repository.findByPlayerId(player, Limit.of(50));
    assertThat(views).hasSize(1);
    assertThat(views.get(0).result()).isEqualTo(GameResult.WHITE_WIN);
  }

  @Test
  void findByPlayerId_legacyNullResult_projectsNull() {
    UUID player = UUID.randomUUID();
    // newGameEntity leaves result null, mirroring a legacy ABANDONED archive whose winner was
    // unrecoverable at backfill time.
    GameEntity game = newGameEntity(UUID.randomUUID(), player, UUID.randomUUID(), "Ann", "Bob");
    repository.save(game);
    repository.flush();

    List<ArchivedGamePlayerView> views = repository.findByPlayerId(player, Limit.of(50));
    assertThat(views).hasSize(1);
    assertThat(views.get(0).result()).isNull();
  }

  @Test
  void findByPlayerId_carriesMoveCountInProjection() {
    UUID player = UUID.randomUUID();
    GameEntity game =
        newGameEntity(UUID.randomUUID(), player, UUID.randomUUID(), "Counter", "OppX");
    game.setMoves(
        List.of(
            new MoveEntity(game, 0, "e2", "e4", null),
            new MoveEntity(game, 1, "e7", "e5", null),
            new MoveEntity(game, 2, "g1", "f3", null)));
    repository.save(game);
    repository.flush();

    List<ArchivedGamePlayerView> views = repository.findByPlayerId(player, Limit.of(50));
    assertThat(views).hasSize(1);
    assertThat(views.get(0).moveCount()).isEqualTo(3);
  }

  private static GameEntity newGameEntity(
      UUID id, UUID whiteId, UUID blackId, String whiteName, String blackName) {
    return new GameEntity(
        id,
        "ROOM01",
        whiteId,
        whiteName,
        blackId,
        blackName,
        // Feature 19 added two nullable FK columns (white_user_id, black_user_id) to GameEntity.
        // This helper builds guest-side games for the legacy player-id history path — both stay
        // null, mirroring "pre-feature-19 archive" semantics.
        null,
        null,
        STARTING_FEN,
        FINAL_FEN,
        GameStatus.CHECKMATE,
        Instant.parse("2026-05-21T10:00:00Z"));
  }
}
