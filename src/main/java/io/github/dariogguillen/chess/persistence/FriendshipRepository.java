package io.github.dariogguillen.chess.persistence;

import io.github.dariogguillen.chess.domain.Friendship;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA repository for {@link Friendship}, introduced by feature 23.8 (friends-list).
 *
 * <p>Beyond the {@code save / findById / delete} surface from {@link JpaRepository}, it carries:
 *
 * <ul>
 *   <li>Pair-existence checks ({@link #findPendingBetween}, {@link #existsAcceptedBetween}) that
 *       the service uses to enforce the SELF/ALREADY/DUPLICATE rules before insert. Each is written
 *       symmetrically — it matches the pair in either direction — so a single query covers both
 *       "A→B" and "B→A".
 *   <li>Participant-scoped single-row fetches ({@link #findPendingByIdAndAddressee}, {@link
 *       #findPendingByIdAndParticipant}) used by accept/reject/cancel. By baking the caller into
 *       the {@code WHERE} clause, "not a participant" collapses to {@code Optional.empty()} — the
 *       service then throws the same 404 as a truly-missing row, which is the no-existence-leak
 *       behaviour.
 *   <li>Paginated projection queries ({@link #findAcceptedFriends}, {@link #findIncomingRequests},
 *       {@link #findOutgoingRequests}) that {@code JOIN User} to resolve the OTHER party's
 *       <em>live</em> display name and friend code at query time.
 * </ul>
 *
 * <p>All ids are {@link UUID} end-to-end — Spring Data binds them straight to the native Postgres
 * {@code uuid} columns with no driver-level conversion.
 */
public interface FriendshipRepository extends JpaRepository<Friendship, UUID> {

  /**
   * Finds a PENDING friendship between the two users in either direction.
   *
   * @param a one user id.
   * @param b the other user id.
   * @return the pending row if one exists for the unordered pair, else empty.
   */
  @Query(
      "SELECT f FROM Friendship f "
          + "WHERE f.status = io.github.dariogguillen.chess.domain.FriendshipStatus.PENDING "
          + "AND ((f.requesterId = :a AND f.addresseeId = :b) "
          + "OR (f.requesterId = :b AND f.addresseeId = :a))")
  Optional<Friendship> findPendingBetween(@Param("a") UUID a, @Param("b") UUID b);

  /**
   * Reports whether an ACCEPTED friendship exists between the two users in either direction.
   *
   * @param a one user id.
   * @param b the other user id.
   * @return {@code true} if the two are already friends.
   */
  @Query(
      "SELECT COUNT(f) > 0 FROM Friendship f "
          + "WHERE f.status = io.github.dariogguillen.chess.domain.FriendshipStatus.ACCEPTED "
          + "AND ((f.requesterId = :a AND f.addresseeId = :b) "
          + "OR (f.requesterId = :b AND f.addresseeId = :a))")
  boolean existsAcceptedBetween(@Param("a") UUID a, @Param("b") UUID b);

  /**
   * Finds a PENDING request by id where the caller is the addressee. Used by the accept endpoint:
   * only the addressee may accept, and a non-addressee (or unknown id) yields empty so the service
   * surfaces the no-leak 404.
   *
   * @param id the request id.
   * @param addresseeId the caller's user id, which must equal the addressee.
   * @return the pending request if the caller is its addressee, else empty.
   */
  @Query(
      "SELECT f FROM Friendship f "
          + "WHERE f.id = :id AND f.addresseeId = :addresseeId "
          + "AND f.status = io.github.dariogguillen.chess.domain.FriendshipStatus.PENDING")
  Optional<Friendship> findPendingByIdAndAddressee(
      @Param("id") UUID id, @Param("addresseeId") UUID addresseeId);

  /**
   * Finds a PENDING request by id where the caller is either participant. Used by the delete
   * endpoint: the requester may cancel and the addressee may reject, both deleting the row. A
   * non-participant (or unknown id) yields empty, again the no-leak 404 path.
   *
   * @param id the request id.
   * @param userId the caller's user id, which must equal the requester or the addressee.
   * @return the pending request if the caller participates in it, else empty.
   */
  @Query(
      "SELECT f FROM Friendship f "
          + "WHERE f.id = :id AND (f.requesterId = :userId OR f.addresseeId = :userId) "
          + "AND f.status = io.github.dariogguillen.chess.domain.FriendshipStatus.PENDING")
  Optional<Friendship> findPendingByIdAndParticipant(
      @Param("id") UUID id, @Param("userId") UUID userId);

  /**
   * Finds the ACCEPTED friendship between the caller and a specific other user, in either
   * direction. Used by the remove-friend endpoint.
   *
   * @param userId the caller's user id.
   * @param otherId the friend's user id.
   * @return the accepted row if the two are friends, else empty.
   */
  @Query(
      "SELECT f FROM Friendship f "
          + "WHERE f.status = io.github.dariogguillen.chess.domain.FriendshipStatus.ACCEPTED "
          + "AND ((f.requesterId = :userId AND f.addresseeId = :otherId) "
          + "OR (f.requesterId = :otherId AND f.addresseeId = :userId))")
  Optional<Friendship> findAcceptedBetween(
      @Param("userId") UUID userId, @Param("otherId") UUID otherId);

  /**
   * Returns the caller's ACCEPTED friends, paginated, projecting the OTHER user's live identity. A
   * {@code CASE} expression selects the side that is not the caller; the join on {@link
   * io.github.dariogguillen.chess.domain.User} pulls that user's current {@code displayName} and
   * {@code friendCode}. Newest friendships first ({@code responded_at} descending).
   *
   * @param userId the caller's user id.
   * @param pageable the page request; sort is ignored (the query has an explicit ORDER BY).
   * @return a page of {@link FriendSummary}.
   */
  @Query(
      "SELECT new io.github.dariogguillen.chess.persistence.FriendSummary("
          + "u.id, u.displayName, u.friendCode, f.respondedAt) "
          + "FROM Friendship f "
          + "JOIN User u ON u.id = CASE WHEN f.requesterId = :userId "
          + "THEN f.addresseeId ELSE f.requesterId END "
          + "WHERE f.status = io.github.dariogguillen.chess.domain.FriendshipStatus.ACCEPTED "
          + "AND (f.requesterId = :userId OR f.addresseeId = :userId) "
          + "ORDER BY f.respondedAt DESC")
  Page<FriendSummary> findAcceptedFriends(@Param("userId") UUID userId, Pageable pageable);

  /**
   * Returns the PENDING requests addressed to the caller (incoming), paginated, projecting the
   * requester's live identity. Newest first.
   *
   * @param userId the caller's user id (the addressee).
   * @param pageable the page request; sort is ignored.
   * @return a page of {@link FriendRequestSummary}.
   */
  @Query(
      "SELECT new io.github.dariogguillen.chess.persistence.FriendRequestSummary("
          + "f.id, u.id, u.displayName, u.friendCode, f.createdAt) "
          + "FROM Friendship f JOIN User u ON u.id = f.requesterId "
          + "WHERE f.addresseeId = :userId "
          + "AND f.status = io.github.dariogguillen.chess.domain.FriendshipStatus.PENDING "
          + "ORDER BY f.createdAt DESC")
  Page<FriendRequestSummary> findIncomingRequests(@Param("userId") UUID userId, Pageable pageable);

  /**
   * Returns the PENDING requests the caller has sent (outgoing), paginated, projecting the
   * addressee's live identity. Newest first.
   *
   * @param userId the caller's user id (the requester).
   * @param pageable the page request; sort is ignored.
   * @return a page of {@link FriendRequestSummary}.
   */
  @Query(
      "SELECT new io.github.dariogguillen.chess.persistence.FriendRequestSummary("
          + "f.id, u.id, u.displayName, u.friendCode, f.createdAt) "
          + "FROM Friendship f JOIN User u ON u.id = f.addresseeId "
          + "WHERE f.requesterId = :userId "
          + "AND f.status = io.github.dariogguillen.chess.domain.FriendshipStatus.PENDING "
          + "ORDER BY f.createdAt DESC")
  Page<FriendRequestSummary> findOutgoingRequests(@Param("userId") UUID userId, Pageable pageable);
}
