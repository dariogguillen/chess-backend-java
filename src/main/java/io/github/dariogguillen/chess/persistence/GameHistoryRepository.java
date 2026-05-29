package io.github.dariogguillen.chess.persistence;

import java.util.List;
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
          + "g.blackDisplayName, g.whiteUserId, g.blackUserId, g.status, g.endedAt, "
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
          + "g.blackDisplayName, g.whiteUserId, g.blackUserId, g.status, g.endedAt, "
          + "SIZE(g.moves)) "
          + "FROM GameEntity g "
          + "WHERE g.whiteUserId = :userId OR g.blackUserId = :userId "
          + "ORDER BY g.endedAt DESC")
  Page<ArchivedGamePlayerView> findByUserId(@Param("userId") UUID userId, Pageable pageable);
}
