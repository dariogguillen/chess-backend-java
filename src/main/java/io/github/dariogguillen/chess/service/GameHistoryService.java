package io.github.dariogguillen.chess.service;

import io.github.dariogguillen.chess.domain.Game;
import io.github.dariogguillen.chess.domain.Side;
import io.github.dariogguillen.chess.exception.GameNotFoundException;
import io.github.dariogguillen.chess.persistence.ArchivedGamePlayerView;
import io.github.dariogguillen.chess.persistence.GameEntity;
import io.github.dariogguillen.chess.persistence.GameEntityMapper;
import io.github.dariogguillen.chess.persistence.GameHistoryRepository;
import io.github.dariogguillen.chess.persistence.MoveEntity;
import io.github.dariogguillen.chess.persistence.UserGameStatsView;
import io.github.dariogguillen.chess.web.game.GameStateResponse.MoveSummary;
import io.github.dariogguillen.chess.web.me.MyGameDetail;
import io.github.dariogguillen.chess.web.me.MyStatsResponse;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

  /**
   * Returns the archived games owned by {@code userId} as either side, page-shaped, newest first.
   * Activated by feature 19 (`auth-my-games`) to back the authenticated {@code GET /api/me/games}
   * endpoint. The user-id-driven query is decoupled from {@link #findByPlayer(UUID)}: the per-
   * session player UUID and the user FK are distinct concepts that travel together on every
   * authenticated game, and the two history endpoints serve two distinct audiences (guests and
   * authenticated users).
   *
   * <p>Unknown user id → empty page. No "user not found" 404 — the controller enforces that the
   * caller IS the user by construction (Spring Security's filter chain 401s otherwise), so the only
   * way to reach this with an unknown id is for the user to have just registered with zero games.
   *
   * @param userId the authenticated user's id; non-null.
   * @param pageable page-shaped request (page index, page size).
   * @return the user's archived games, newest first, page envelope.
   */
  @Transactional(readOnly = true)
  public Page<ArchivedGamePlayerView> findByUser(UUID userId, Pageable pageable) {
    return repository.findByUserId(userId, pageable);
  }

  /**
   * Returns the aggregate win/loss/draw record for {@code userId}, computed by a single JPQL
   * conditional-sum query that never loads the game rows. Added by feature 23.93 (`me-stats`) to
   * back the authenticated {@code GET /api/me/stats} endpoint.
   *
   * <p>The four buckets reconcile with {@code total} ({@code total == wins + losses + draws +
   * unknown}); {@code unknown} holds legacy NULL-result games (old ABANDONED rows). The {@code
   * winRate} is computed here — not in JPQL — over decided games ({@code wins + losses + draws})
   * with a divide-by-zero guard that returns {@code 0.0} when no games are decided.
   *
   * <p>A user with zero games yields {@code total=0}, all-zero buckets, and {@code winRate=0.0}:
   * the repository's {@code COALESCE(SUM(...), 0L)} guards the empty aggregate, so this method
   * never sees a {@code null} view or a {@code null} sum.
   *
   * @param userId the authenticated user's id; non-null.
   * @return the user's aggregate record as a {@link MyStatsResponse}.
   */
  @Transactional(readOnly = true)
  public MyStatsResponse statsFor(UUID userId) {
    UserGameStatsView view = repository.statsForUser(userId);
    return MyStatsResponse.from(view);
  }

  /**
   * Returns one archived game with its FULL ordered move list, for the authenticated {@code GET
   * /api/me/games/{id}} replay endpoint (feature 23.94, `game-review`). The lookup is scoped to the
   * caller being a participant: the underlying {@link GameHistoryRepository#findByIdForUser(UUID,
   * UUID)} query restricts to {@code white_user_id = userId OR black_user_id = userId}, so a
   * non-participant, an unknown game id, and an anonymous game (both user ids null) are all
   * indistinguishable — every one yields an empty result and this method throws {@link
   * GameNotFoundException} (→ 404 {@code GAME_NOT_FOUND}, the no-leak contract).
   *
   * <p>{@code @Transactional(readOnly = true)} keeps the persistence context open across the
   * entity-to-DTO mapping below. With {@code spring.jpa.open-in-view = false} the session is closed
   * by the time the controller maps, so the mapping MUST run inside this method; the {@code LEFT
   * JOIN FETCH g.moves} in the query also initialises the lazy {@code moves} collection in the same
   * round-trip, so {@link #toDetail(GameEntity, UUID)} touches no un-fetched association.
   *
   * @param gameId the archived game's id; non-null.
   * @param userId the authenticated caller's id; non-null.
   * @return the game projected into a {@link MyGameDetail}.
   * @throws GameNotFoundException if no archived game with that id exists for which the caller was
   *     a participant.
   */
  @Transactional(readOnly = true)
  public MyGameDetail detailForUser(UUID gameId, UUID userId) {
    GameEntity entity =
        repository
            .findByIdForUser(gameId, userId)
            .orElseThrow(() -> new GameNotFoundException(gameId));
    return toDetail(entity, userId);
  }

  /**
   * Maps a fetched {@link GameEntity} (with its {@code moves} already initialised by the {@code
   * LEFT JOIN FETCH}) to a {@link MyGameDetail}, deriving {@code selfSide} from whether {@code
   * userId} matches {@code whiteUserId}. Called only from {@link #detailForUser(UUID, UUID)} while
   * the transaction is still open.
   */
  private static MyGameDetail toDetail(GameEntity entity, UUID userId) {
    Side selfSide = userId.equals(entity.getWhiteUserId()) ? Side.WHITE : Side.BLACK;
    List<MoveSummary> moves =
        entity.getMoves().stream().map(GameHistoryService::toMoveSummary).toList();
    return new MyGameDetail(
        entity.getId(),
        entity.getRoomId(),
        entity.getWhiteDisplayName(),
        entity.getBlackDisplayName(),
        selfSide,
        entity.getStatus(),
        entity.getResult(),
        entity.getStartingFen(),
        entity.getFinalFen(),
        entity.getEndedAt(),
        moves);
  }

  /** Projects a persisted {@link MoveEntity} to the wire-format {@link MoveSummary} shape. */
  private static MoveSummary toMoveSummary(MoveEntity move) {
    return new MoveSummary(move.getFromSquare(), move.getToSquare(), move.getPromotion());
  }
}
