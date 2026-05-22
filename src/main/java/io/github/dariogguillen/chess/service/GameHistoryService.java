package io.github.dariogguillen.chess.service;

import io.github.dariogguillen.chess.domain.Game;
import io.github.dariogguillen.chess.persistence.ArchivedGamePlayerView;
import io.github.dariogguillen.chess.persistence.GameEntity;
import io.github.dariogguillen.chess.persistence.GameEntityMapper;
import io.github.dariogguillen.chess.persistence.GameHistoryRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use-case orchestration for the Postgres-backed game history archive. Two operations:
 *
 * <ul>
 *   <li>{@link #archive(Game)} — write-on-terminal-status, called by {@code GameService.applyMove}
 *       whenever a move flips the game into a terminal {@code GameStatus}.
 *   <li>{@link #findByPlayer(UUID)} — read-on-history-query, called by {@code
 *       PlayerGamesController} to populate the {@code GET /api/players/{id}/games} endpoint.
 * </ul>
 *
 * <p>The active state in Redis is <strong>not</strong> replaced by this service — the Redis {@code
 * game:{id}} entry remains the source of truth for ongoing games. Postgres is strictly the archive
 * layer. The two stores can briefly diverge if Postgres throws while Redis succeeds, or vice-versa;
 * the {@code GameService.applyMove} ordering is the safe choice (see that method's doc).
 */
@Service
public class GameHistoryService {

  /** Hard cap on the number of history entries returned per player. No pagination param exposed. */
  static final int HISTORY_HARD_CAP = 50;

  private static final Logger log = LoggerFactory.getLogger(GameHistoryService.class);

  private final GameHistoryRepository repository;
  private final GameEntityMapper mapper;

  public GameHistoryService(GameHistoryRepository repository, GameEntityMapper mapper) {
    this.repository = repository;
    this.mapper = mapper;
  }

  /**
   * Persists {@code game} to the Postgres archive.
   *
   * <p>The operation is <em>idempotent</em>: re-archiving the same game id overwrites the existing
   * row(s) because {@link GameHistoryRepository#save(Object)} resolves to a merge on a managed
   * entity with the same primary key. The orphan-removal cascade on {@link GameEntity#getMoves()}
   * means the existing move rows are replaced wholesale rather than being merged side-by-side.
   *
   * @param game the terminal-status domain game to archive; non-null. This service does not check
   *     {@code game.status().isTerminal()} — that gate lives in {@code GameService.applyMove},
   *     which is the sole caller in production. The IT exercises both shapes (terminal and
   *     non-terminal) directly.
   */
  @Transactional
  public void archive(Game game) {
    GameEntity entity = mapper.toEntity(game);
    repository.save(entity);
    log.info(
        "Archived game: id={}, status={}, moves={}", game.id(), game.status(), game.moves().size());
  }

  /**
   * Returns the archived games for {@code playerId}, newest first, capped at {@value
   * #HISTORY_HARD_CAP} entries. The query matches the player as either side; the returned entities
   * are detached value objects suitable for projection into a response DTO at the controller layer.
   *
   * <p>An unknown player id (one that has never played a terminal game) returns an empty list.
   * Players are guest UUIDs with no registry; there is no "404 player not found" — the empty list
   * is the honest answer.
   *
   * @param playerId the player id to look up; non-null.
   * @return the player's archived games, newest first, at most {@value #HISTORY_HARD_CAP} entries.
   */
  @Transactional(readOnly = true)
  public List<ArchivedGamePlayerView> findByPlayer(UUID playerId) {
    return repository.findByPlayerId(playerId, Limit.of(HISTORY_HARD_CAP));
  }
}
