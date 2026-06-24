package io.github.dariogguillen.chess.service;

import io.github.dariogguillen.chess.domain.Friendship;
import io.github.dariogguillen.chess.domain.User;
import io.github.dariogguillen.chess.exception.AlreadyFriendsException;
import io.github.dariogguillen.chess.exception.DuplicateFriendRequestException;
import io.github.dariogguillen.chess.exception.FriendCodeNotFoundException;
import io.github.dariogguillen.chess.exception.FriendNotFoundException;
import io.github.dariogguillen.chess.exception.FriendRequestNotFoundException;
import io.github.dariogguillen.chess.exception.SelfFriendshipException;
import io.github.dariogguillen.chess.persistence.FriendRequestSummary;
import io.github.dariogguillen.chess.persistence.FriendSummary;
import io.github.dariogguillen.chess.persistence.FriendshipRepository;
import io.github.dariogguillen.chess.persistence.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the friends-list lifecycle (feature 23.8): send a request, accept it, reject/cancel it,
 * remove an accepted friend, and the three paginated read surfaces (friends, incoming requests,
 * outgoing requests). Controllers stay routing-only — they translate the authenticated {@link User}
 * and HTTP inputs into a call here and the result back to a response.
 *
 * <p><strong>No existence leak.</strong> The accept/reject/cancel paths fetch the row with the
 * caller baked into the {@code WHERE} clause (see {@link FriendshipRepository}). A request the
 * caller cannot act on — because it does not exist, or because the caller is not a participant —
 * yields {@link java.util.Optional#empty()} indistinguishably, and this service throws the same
 * {@link FriendRequestNotFoundException} (404) for both. We deliberately never return 403, which
 * would confirm a request exists and let an attacker probe for valid ids.
 *
 * <p><strong>Unordered-pair uniqueness.</strong> The {@link #sendRequest} guards (self / already
 * friends / duplicate pending) are application-level pre-checks; the database UNIQUE index on the
 * unordered {@code (requester_id, addressee_id)} pair is the race-condition safety net behind them.
 * A concurrent both-directions send that slips past the pre-check is caught as a {@link
 * DataIntegrityViolationException} on insert and translated into the same {@link
 * DuplicateFriendRequestException} the pre-check would have thrown.
 */
@Service
public class FriendshipService {

  private static final Logger log = LoggerFactory.getLogger(FriendshipService.class);

  private final FriendshipRepository friendships;
  private final UserRepository users;
  private final Clock clock;

  public FriendshipService(FriendshipRepository friendships, UserRepository users, Clock clock) {
    this.friendships = friendships;
    this.users = users;
    this.clock = clock;
  }

  /**
   * Sends a PENDING friend request from {@code requesterId} to the user owning {@code friendCode}.
   *
   * @param requesterId the authenticated caller's user id.
   * @param friendCode the addressee's shareable friend code.
   * @return the created friendship row.
   * @throws FriendCodeNotFoundException if no user owns the code (404).
   * @throws SelfFriendshipException if the code belongs to the caller (422).
   * @throws AlreadyFriendsException if an ACCEPTED friendship already exists (409).
   * @throws DuplicateFriendRequestException if a PENDING request already exists in either direction
   *     (409).
   */
  @Transactional
  public Friendship sendRequest(UUID requesterId, String friendCode) {
    User addressee =
        users
            .findByFriendCode(friendCode)
            .orElseThrow(() -> new FriendCodeNotFoundException(friendCode));
    UUID addresseeId = addressee.getId();

    if (addresseeId.equals(requesterId)) {
      throw new SelfFriendshipException();
    }
    if (friendships.existsAcceptedBetween(requesterId, addresseeId)) {
      throw new AlreadyFriendsException();
    }
    if (friendships.findPendingBetween(requesterId, addresseeId).isPresent()) {
      throw new DuplicateFriendRequestException();
    }

    Friendship friendship =
        new Friendship(UUID.randomUUID(), requesterId, addresseeId, Instant.now(clock));
    Friendship saved;
    try {
      saved = friendships.save(friendship);
    } catch (DataIntegrityViolationException ex) {
      // Race-condition safety net: two concurrent sends for the same unordered pair both pass the
      // pre-checks, but the DB UNIQUE index rejects the second insert. Surface the same 409.
      throw new DuplicateFriendRequestException();
    }
    log.info("Friend request created: requester={}, addressee={}", requesterId, addresseeId);
    return saved;
  }

  /**
   * Accepts a PENDING request. Only the addressee may accept.
   *
   * @param requestId the friendship row id.
   * @param addresseeId the authenticated caller's user id; must be the request's addressee.
   * @throws FriendRequestNotFoundException if the request does not exist or the caller is not its
   *     addressee (404, no existence leak).
   */
  @Transactional
  public void acceptRequest(UUID requestId, UUID addresseeId) {
    Friendship request =
        friendships
            .findPendingByIdAndAddressee(requestId, addresseeId)
            .orElseThrow(() -> new FriendRequestNotFoundException(requestId));
    request.accept(Instant.now(clock));
    friendships.save(request);
    log.info("Friend request accepted: id={}, addressee={}", requestId, addresseeId);
  }

  /**
   * Deletes a PENDING request. The requester may cancel and the addressee may reject — both delete
   * the row outright (there is no REJECTED tombstone state).
   *
   * @param requestId the friendship row id.
   * @param userId the authenticated caller's user id; must be the requester or the addressee.
   * @throws FriendRequestNotFoundException if the request does not exist or the caller is not a
   *     participant (404, no existence leak).
   */
  @Transactional
  public void deleteRequest(UUID requestId, UUID userId) {
    Friendship request =
        friendships
            .findPendingByIdAndParticipant(requestId, userId)
            .orElseThrow(() -> new FriendRequestNotFoundException(requestId));
    friendships.delete(request);
    log.info("Friend request deleted: id={}, by={}", requestId, userId);
  }

  /**
   * Removes an ACCEPTED friendship between the caller and {@code friendUserId}. Either party may
   * remove; the row is deleted.
   *
   * @param userId the authenticated caller's user id.
   * @param friendUserId the friend's user id.
   * @throws FriendNotFoundException if no accepted friendship exists with that user (404).
   */
  @Transactional
  public void removeFriend(UUID userId, UUID friendUserId) {
    Friendship friendship =
        friendships
            .findAcceptedBetween(userId, friendUserId)
            .orElseThrow(() -> new FriendNotFoundException(friendUserId));
    friendships.delete(friendship);
    log.info("Friendship removed: user={}, friend={}", userId, friendUserId);
  }

  /**
   * Returns the caller's ACCEPTED friends, paginated, projecting each friend's live display name
   * and friend code.
   *
   * @param userId the authenticated caller's user id.
   * @param pageable the page request.
   * @return a page of {@link FriendSummary}.
   */
  @Transactional(readOnly = true)
  public Page<FriendSummary> listFriends(UUID userId, Pageable pageable) {
    return friendships.findAcceptedFriends(userId, pageable);
  }

  /**
   * Returns the PENDING requests addressed to the caller (incoming), paginated.
   *
   * @param userId the authenticated caller's user id.
   * @param pageable the page request.
   * @return a page of {@link FriendRequestSummary}.
   */
  @Transactional(readOnly = true)
  public Page<FriendRequestSummary> listIncomingRequests(UUID userId, Pageable pageable) {
    return friendships.findIncomingRequests(userId, pageable);
  }

  /**
   * Returns the PENDING requests the caller has sent (outgoing), paginated.
   *
   * @param userId the authenticated caller's user id.
   * @param pageable the page request.
   * @return a page of {@link FriendRequestSummary}.
   */
  @Transactional(readOnly = true)
  public Page<FriendRequestSummary> listOutgoingRequests(UUID userId, Pageable pageable) {
    return friendships.findOutgoingRequests(userId, pageable);
  }
}
