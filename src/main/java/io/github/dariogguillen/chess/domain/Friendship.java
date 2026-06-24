package io.github.dariogguillen.chess.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A symmetric request/accept friendship between two {@link User}s, introduced by feature 23.8
 * (friends-list). Mapped to the {@code friendships} table created by {@code
 * V3__add_friend_code_and_friendships.sql}.
 *
 * <p>Storage follows the {@code games} house style: the two participants are stored as raw {@link
 * UUID}s ({@code requester_id}, {@code addressee_id}) with NO {@code @ManyToOne} associations. The
 * live display names the list endpoints return are NOT snapshotted here — they are resolved at
 * query time via a Hibernate entity join in JPQL ({@code JOIN User u ON u.id = f.addresseeId}), so
 * a friend's later rename is reflected. This is the opposite choice from {@code games}, which DOES
 * snapshot the display name because a game is an audit record; a friendship is a live relationship,
 * so the live name is the correct one.
 *
 * <p>Direction matters only while {@link FriendshipStatus#PENDING}: the {@code requester} sent the
 * request and may cancel it; the {@code addressee} received it and may accept or reject it. Once
 * {@link FriendshipStatus#ACCEPTED}, the relationship is symmetric — either party can remove it and
 * each sees the other in their friends list. A database UNIQUE index on the unordered pair {@code
 * (LEAST(requester_id, addressee_id), GREATEST(requester_id, addressee_id))} guarantees at most one
 * row exists per pair regardless of who sent the request, which is what makes a both-directions
 * duplicate a constraint violation rather than a race-prone application check.
 *
 * <p>This is a JPA entity, not a record, for the same reason {@link User} is: the JPA spec requires
 * a no-args constructor and writable fields, both incompatible with a record. Mutation is contained
 * — the only state transition after creation is {@link #accept(Instant)}, exposed as a single
 * intent-named method rather than open setters.
 */
@Entity
@Table(name = "friendships")
public class Friendship {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "requester_id", nullable = false)
  private UUID requesterId;

  @Column(name = "addressee_id", nullable = false)
  private UUID addresseeId;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private FriendshipStatus status;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "responded_at")
  private Instant respondedAt;

  /** Required by JPA. Not part of the public API of this class. */
  protected Friendship() {}

  /**
   * Constructs a fresh PENDING friendship request from {@code requesterId} to {@code addresseeId}.
   * The caller is expected to have already validated that the two ids differ (self-friendship is
   * rejected at the service boundary) and that no relationship already exists for the pair.
   *
   * @param id the friendship identifier; non-null.
   * @param requesterId the user who sent the request; non-null.
   * @param addresseeId the user who received the request; non-null.
   * @param createdAt the audit timestamp; non-null.
   */
  public Friendship(UUID id, UUID requesterId, UUID addresseeId, Instant createdAt) {
    this.id = id;
    this.requesterId = requesterId;
    this.addresseeId = addresseeId;
    this.status = FriendshipStatus.PENDING;
    this.createdAt = createdAt;
    this.respondedAt = null;
  }

  /**
   * Flips this PENDING request to ACCEPTED and records the response time. Intended to be called by
   * {@code FriendshipService} only after it has verified the caller is the addressee and the
   * current status is PENDING.
   *
   * @param respondedAt the instant the request was accepted; becomes the "friends since" value.
   */
  public void accept(Instant respondedAt) {
    this.status = FriendshipStatus.ACCEPTED;
    this.respondedAt = respondedAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getRequesterId() {
    return requesterId;
  }

  public UUID getAddresseeId() {
    return addresseeId;
  }

  public FriendshipStatus getStatus() {
    return status;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getRespondedAt() {
    return respondedAt;
  }
}
