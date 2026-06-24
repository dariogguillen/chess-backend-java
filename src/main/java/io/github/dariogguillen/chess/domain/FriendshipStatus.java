package io.github.dariogguillen.chess.domain;

/**
 * The lifecycle state of a {@link Friendship}. A small, closed set — exactly two states.
 *
 * <p>There is deliberately no {@code REJECTED} state: a reject, a cancel, and a remove all DELETE
 * the row (feature 23.8 plan, locked with the user). The relationship therefore only ever exists in
 * one of these two states while a row is present, and re-requesting after a reject is valid because
 * no tombstone row survives to block it.
 *
 * <ul>
 *   <li>{@link #PENDING} — A sent a request to B; awaiting B's accept-or-delete.
 *   <li>{@link #ACCEPTED} — B accepted; A and B are friends. The {@code responded_at} timestamp is
 *       set at this transition and serves as the "friends since" value the list endpoint projects.
 * </ul>
 */
public enum FriendshipStatus {
  PENDING,
  ACCEPTED
}
