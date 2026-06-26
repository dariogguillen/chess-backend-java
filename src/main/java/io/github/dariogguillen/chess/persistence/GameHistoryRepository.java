package io.github.dariogguillen.chess.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA repository for archived games.
 *
 * <p>{@link JpaRepository} provides the {@code save / findById / delete / count} surface, and the
 * JPQL-based custom methods below cover the two history queries:
 *
 * <ul>
 *   <li>{@link #findByPlayerId(UUID, Limit)} — the per-session player-id history (the existing
 *       guest-friendly {@code GET /api/players/{id}/games} surface).
 *   <li>{@link #findByUserId(UUID, Pageable)} — the per-user history added by feature 19
 *       (`auth-my-games`) to back the authenticated {@code GET /api/me/games} endpoint. Spring Data
 *       derives the count query for {@link Page} automatically from the JPQL above; the {@code
 *       SIZE(g.moves)} sub-projection is excluded from the count, so the count query stays a
 *       single-row scan of the partial indexes from V2.
 * </ul>
 *
 * <p>Both queries project into {@link ArchivedGamePlayerView}, a record whose canonical constructor
 * matches the SELECT-list order. The same projection is reused so the controller layer maps to its
 * wire-format DTO uniformly — only the {@code WHERE} clause differs.
 *
 * <p>The id parameters are {@link UUID} end-to-end — Spring Data binds them straight to the native
 * Postgres {@code uuid} columns with no driver-level conversion.
 */
public interface GameHistoryRepository extends JpaRepository<GameEntity, UUID> {

  /**
   * Returns archived games where {@code playerId} matched either the white or the black side, in
   * descending {@code endedAt} order, projected into {@link ArchivedGamePlayerView} so the move
   * count is computed in the same SQL round-trip. {@link Limit} caps the result count at the call
   * site (the service passes {@code Limit.of(50)} — the hard cap documented in the architecture
   * note).
   *
   * <p>{@code SIZE(g.moves)} translates to a correlated {@code COUNT(*)} subquery on {@code moves};
   * for the volumes this project sees (one archive row per terminal game, capped at 50 per query),
   * the planner picks the {@code idx_games_*_player} index and the subquery is negligible. The
   * alternative — fetching the entity with a {@code JOIN FETCH g.moves} — would materialise every
   * move row into the JDBC result set just to discard everything except the count.
   *
   * @param playerId the player id to look up; matched against both white_player_id and
   *     black_player_id.
   * @param limit hard cap on the number of rows returned; the service imposes 50.
   * @return the player's archived games, newest first, at most {@code limit} entries.
   */
  @Query(
      "SELECT new io.github.dariogguillen.chess.persistence.ArchivedGamePlayerView("
          + "g.id, g.roomId, g.whitePlayerId, g.whiteDisplayName, g.blackPlayerId, "
          + "g.blackDisplayName, g.whiteUserId, g.blackUserId, g.status, g.result, g.endedAt, "
          + "SIZE(g.moves)) "
          + "FROM GameEntity g "
          + "WHERE g.whitePlayerId = :playerId OR g.blackPlayerId = :playerId "
          + "ORDER BY g.endedAt DESC")
  List<ArchivedGamePlayerView> findByPlayerId(@Param("playerId") UUID playerId, Limit limit);

  /**
   * Returns archived games where {@code userId} matched either the white or the black FK side, in
   * descending {@code endedAt} order, paginated. Activated by feature 19 (`auth-my-games`): backs
   * the {@code GET /api/me/games?page=&size=} endpoint. The {@code WHERE} clause hits the two
   * partial indexes created by V2 ({@code idx_games_white_user_id} and {@code
   * idx_games_black_user_id}, both scoped to {@code WHERE *_user_id IS NOT NULL}), so guest games
   * are excluded by index design rather than at scan time.
   *
   * <p>Spring Data derives the {@code COUNT(*)} query for the {@link Page} envelope automatically:
   * {@code SELECT COUNT(g) FROM GameEntity g WHERE g.whiteUserId = :userId OR g.blackUserId =
   * :userId}. The {@code SIZE(g.moves)} subquery is dropped from the count query because it has no
   * effect on row count.
   *
   * @param userId the authenticated user id to look up; matched against both white_user_id and
   *     black_user_id.
   * @param pageable the page request (page, size, sort). Sort is ignored — the query has an
   *     explicit {@code ORDER BY g.endedAt DESC} that mirrors the existing player-history path.
   * @return the user's archived games, newest first, page-shaped envelope.
   */
  @Query(
      "SELECT new io.github.dariogguillen.chess.persistence.ArchivedGamePlayerView("
          + "g.id, g.roomId, g.whitePlayerId, g.whiteDisplayName, g.blackPlayerId, "
          + "g.blackDisplayName, g.whiteUserId, g.blackUserId, g.status, g.result, g.endedAt, "
          + "SIZE(g.moves)) "
          + "FROM GameEntity g "
          + "WHERE g.whiteUserId = :userId OR g.blackUserId = :userId "
          + "ORDER BY g.endedAt DESC")
  Page<ArchivedGamePlayerView> findByUserId(@Param("userId") UUID userId, Pageable pageable);

  /**
   * Returns the user's aggregate win/loss/draw record as a single-row {@link UserGameStatsView},
   * computed entirely in SQL with conditional sums — the game rows are never loaded. Added by
   * feature 23.93 (`me-stats`) to back the authenticated {@code GET /api/me/stats} endpoint. The
   * {@code WHERE} clause hits the same two partial indexes ({@code idx_games_white_user_id} /
   * {@code idx_games_black_user_id}) as {@link #findByUserId(UUID, Pageable)}, so guest games never
   * enter the aggregate.
   *
   * <p>Each bucket is a {@code COALESCE(SUM(CASE WHEN ... THEN 1L ELSE 0L END), 0L)}: the {@code
   * COALESCE} is essential because {@code SUM} over zero matched rows returns {@code NULL} in SQL,
   * which would bind {@code null} into the {@code long} record components and throw. Wrapping each
   * sum in {@code COALESCE(..., 0L)} makes the zero-games case yield {@code total=0} with all-zero
   * buckets, never a {@code NullPointerException}.
   *
   * <p>The win/loss derivation cross-references {@code result} with the user's side: a win is
   * {@code (result = WHITE_WIN AND user is white)} OR {@code (result = BLACK_WIN AND user is
   * black)}; a loss is the mirror; {@code result = DRAW} is a draw; {@code result IS NULL} (legacy
   * ABANDONED rows whose winner is unrecoverable) is counted in {@code total} but bucketed as
   * {@code unknown}, excluded from W/L/D. The enum literals are fully-qualified in the JPQL string,
   * matching the house style (see {@link FriendshipRepository}'s {@code FriendshipStatus} usage).
   * The {@code winRate} is NOT computed here — that division is a Java concern (divide-by-zero
   * guard) in {@link io.github.dariogguillen.chess.service.GameHistoryService}.
   *
   * @param userId the authenticated user's id; matched against both white_user_id and
   *     black_user_id.
   * @return a single-row aggregate; for a user with zero games, {@code total=0} and all-zero
   *     buckets.
   */
  @Query(
      "SELECT new io.github.dariogguillen.chess.persistence.UserGameStatsView("
          + "COUNT(g), "
          + "COALESCE(SUM(CASE WHEN "
          + "(g.result = io.github.dariogguillen.chess.domain.GameResult.WHITE_WIN "
          + "AND g.whiteUserId = :userId) "
          + "OR (g.result = io.github.dariogguillen.chess.domain.GameResult.BLACK_WIN "
          + "AND g.blackUserId = :userId) "
          + "THEN 1L ELSE 0L END), 0L), "
          + "COALESCE(SUM(CASE WHEN "
          + "(g.result = io.github.dariogguillen.chess.domain.GameResult.WHITE_WIN "
          + "AND g.blackUserId = :userId) "
          + "OR (g.result = io.github.dariogguillen.chess.domain.GameResult.BLACK_WIN "
          + "AND g.whiteUserId = :userId) "
          + "THEN 1L ELSE 0L END), 0L), "
          + "COALESCE(SUM(CASE WHEN "
          + "g.result = io.github.dariogguillen.chess.domain.GameResult.DRAW "
          + "THEN 1L ELSE 0L END), 0L), "
          + "COALESCE(SUM(CASE WHEN g.result IS NULL THEN 1L ELSE 0L END), 0L)) "
          + "FROM GameEntity g "
          + "WHERE g.whiteUserId = :userId OR g.blackUserId = :userId")
  UserGameStatsView statsForUser(@Param("userId") UUID userId);

  /**
   * Returns ONE archived game by id, restricted to the caller being a participant — the {@code
   * userId} must match either {@code white_user_id} or {@code black_user_id}. Added by feature
   * 23.94 (`game-review`) to back the authenticated {@code GET /api/me/games/{id}} endpoint.
   * Folding the authorization into the {@code WHERE} clause makes the "not found" and "not a
   * participant" cases structurally indistinguishable: both yield an empty {@link Optional}, which
   * the service maps to a single 404 {@code GAME_NOT_FOUND} (no leak of a game's existence to a
   * non-participant). An anonymous game (both user-id columns {@code null}) never matches a
   * non-null {@code userId}, so it is also a 404 for any authenticated caller.
   *
   * <p><strong>{@code LEFT JOIN FETCH g.moves} (LEFT, not inner).</strong> The {@code moves}
   * association is {@link jakarta.persistence.FetchType#LAZY} and {@code spring.jpa.open-in-view}
   * is {@code false}, so without a fetch-join the move list would not be initialised inside the
   * transaction and the service-layer mapping would throw a {@code LazyInitializationException}.
   * The fetch-join loads the moves in the same round-trip. It must be a {@code LEFT} join: a game
   * that terminated before any move was played (e.g. a timeout/abandon with zero moves) has no rows
   * in {@code moves}, and an {@code INNER JOIN FETCH} would silently drop that game from the result
   * — the caller would get a spurious 404 for a game they actually played. {@code LEFT} returns the
   * game with an empty move list, which is the correct replay payload.
   *
   * <p>{@code @OrderBy("moveIdx ASC")} on {@link GameEntity#getMoves()} guarantees the fetched
   * moves arrive in playback order, so the service maps them without re-sorting.
   *
   * @param gameId the archived game's id to look up.
   * @param userId the authenticated caller's id; must match one side for the game to be returned.
   * @return the game with its moves eagerly fetched, or empty when no such game exists OR the
   *     caller is not a participant.
   */
  @Query(
      "SELECT g FROM GameEntity g LEFT JOIN FETCH g.moves "
          + "WHERE g.id = :gameId AND (g.whiteUserId = :userId OR g.blackUserId = :userId)")
  Optional<GameEntity> findByIdForUser(@Param("gameId") UUID gameId, @Param("userId") UUID userId);
}
