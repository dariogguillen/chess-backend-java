package io.github.dariogguillen.chess.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An ephemeral direct invitation (feature 23.9, {@code direct-invitations}) from one authenticated
 * user (the inviter) to one of their ACCEPTED friends (the invitee) to join a specific FRIEND
 * {@link Room} the inviter already created.
 *
 * <p>The invitation deliberately carries only what the invitee's UI needs <em>plus</em> what the
 * inviter's identity requires — it does <strong>not</strong> carry the room's secret {@link
 * Room#joinToken()}. The token stays server-side: at accept time {@code InvitationService} reads it
 * from the live {@link Room} and performs the join via {@code RoomService.joinRoom}, so the token
 * never travels to the invitee's client. The "side the invitee would take" and the time control are
 * <em>not</em> stored here either — both are derived from the live {@link Room} at read time, which
 * keeps the stored value minimal and never stale on those axes.
 *
 * <p>Persistence is ephemeral: the invitation lives in Redis (see {@code RedisInvitationStore}),
 * keyed per invitee, with a TTL aligned to the room's active-state TTL. There is no Flyway
 * migration and no durable table — an invitation that outlives its room is meaningless, so its
 * liveness is re-validated against the live {@link Room} at read/accept time and pruned lazily when
 * the room is gone, full, or no longer joinable.
 *
 * @param roomId the id of the FRIEND room the invitee is invited to join; not null/blank.
 * @param inviterUserId the authenticated user id of the inviter; not null. Used to address the
 *     decline push and to render "X invited you" on the invitee's UI.
 * @param inviterDisplayName the inviter's human-readable label, captured at send time so the
 *     invitee's list does not need a second lookup; not null.
 * @param createdAt the instant the invitation was created (or last refreshed by a re-send); not
 *     null. Surfaced so the UI can order or age invitations.
 */
public record Invitation(
    String roomId, UUID inviterUserId, String inviterDisplayName, Instant createdAt) {

  public Invitation {
    Objects.requireNonNull(roomId, "roomId");
    Objects.requireNonNull(inviterUserId, "inviterUserId");
    Objects.requireNonNull(inviterDisplayName, "inviterDisplayName");
    Objects.requireNonNull(createdAt, "createdAt");
    if (roomId.isBlank()) {
      throw new IllegalArgumentException("Invitation roomId must not be blank");
    }
  }
}
