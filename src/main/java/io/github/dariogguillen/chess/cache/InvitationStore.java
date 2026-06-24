package io.github.dariogguillen.chess.cache;

import io.github.dariogguillen.chess.domain.Invitation;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Storage seam for ephemeral {@link Invitation}s (feature 23.9, {@code direct-invitations}). Keyed
 * per invitee: an invitee has at most one invitation per room, so the natural key is the pair
 * {@code (inviteeUserId, roomId)}.
 *
 * <p>The seam is intentionally backed by a per-invitee index rather than a {@code SCAN}-by-pattern
 * over the keyspace — {@link #findByInvitee(UUID)} must be a single indexed read, which the Redis
 * implementation gets from a per-invitee hash whose fields are the room ids.
 *
 * <p>The store carries no liveness logic of its own: it does not know whether a room still exists,
 * is full, or is joinable. That re-validation against the live {@code RoomStore} (and the lazy
 * pruning of stale entries) lives in {@code InvitationService}, which is the only consumer with the
 * {@code RoomStore} dependency. The store is a dumb, TTL-bounded key/value index.
 */
public interface InvitationStore {

  /**
   * Stores (or overwrites) the invitation to {@code inviteeUserId} for {@code invitation.roomId()}.
   * A second call for the same {@code (inviteeUserId, roomId)} pair overwrites the prior value —
   * the idempotent re-send semantics — and refreshes the TTL.
   *
   * @param inviteeUserId the user being invited.
   * @param invitation the invitation value; its {@code roomId} is the per-invitee index field.
   */
  void save(UUID inviteeUserId, Invitation invitation);

  /**
   * Looks up the single invitation to {@code inviteeUserId} for {@code roomId}, if present.
   *
   * @param inviteeUserId the invited user.
   * @param roomId the room the invitation targets.
   * @return the invitation, or empty if none exists for the pair.
   */
  Optional<Invitation> find(UUID inviteeUserId, String roomId);

  /**
   * Returns every invitation currently addressed to {@code inviteeUserId}. Backed by the
   * per-invitee index — no keyspace scan. The returned entries are unvalidated against room
   * liveness; the caller prunes stale ones.
   *
   * @param inviteeUserId the invited user.
   * @return the (possibly empty) list of invitations addressed to the user.
   */
  List<Invitation> findByInvitee(UUID inviteeUserId);

  /**
   * Removes the invitation to {@code inviteeUserId} for {@code roomId}, if any.
   *
   * @param inviteeUserId the invited user.
   * @param roomId the room the invitation targets.
   * @return {@code true} if an entry existed and was removed, {@code false} otherwise.
   */
  boolean delete(UUID inviteeUserId, String roomId);
}
