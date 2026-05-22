package io.github.dariogguillen.chess.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA repository for archived games.
 *
 * <p>{@link JpaRepository} provides the {@code save / findById / delete / count} surface, and the
 * JPQL-based custom one below covers the "games where the player was either white or black, newest
 * first" history query as a projection that already carries the move count — that avoids a second
 * query (or a {@code LazyInitializationException}, since the application runs with {@code
 * spring.jpa.open-in-view: false}).
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
          + "g.blackDisplayName, g.status, g.endedAt, SIZE(g.moves)) "
          + "FROM GameEntity g "
          + "WHERE g.whitePlayerId = :playerId OR g.blackPlayerId = :playerId "
          + "ORDER BY g.endedAt DESC")
  List<ArchivedGamePlayerView> findByPlayerId(@Param("playerId") UUID playerId, Limit limit);
}
