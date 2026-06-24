package io.github.dariogguillen.chess.persistence;

import java.time.Instant;
import java.util.UUID;

/**
 * JPQL constructor-projection of one ACCEPTED friend, used by {@link
 * FriendshipRepository#findAcceptedFriends(UUID, org.springframework.data.domain.Pageable)} to back
 * {@code GET /api/me/friends}.
 *
 * <p>The projection carries the OTHER user's identity (never the caller's): the repository query
 * uses a {@code CASE} expression to pick whichever side of the friendship is not the caller, then
 * joins {@link io.github.dariogguillen.chess.domain.User} by that id to pull the user's
 * <em>live</em> {@code displayName} and {@code friendCode}. Resolving the name at query time
 * (entity join) rather than from a stored snapshot is the deliberate "a rename is reflected"
 * decision for friendships — see {@link io.github.dariogguillen.chess.domain.Friendship} for why
 * this differs from {@code games}.
 *
 * <p>The view is a plain immutable record — JPQL's {@code SELECT new <fqn>(...)} invokes the
 * canonical constructor at result-set extraction time. Mirrors the {@link ArchivedGamePlayerView}
 * style.
 *
 * @param userId the friend's user id (the other party of the friendship).
 * @param displayName the friend's current display name (live, via entity join).
 * @param friendCode the friend's shareable code.
 * @param friendsSince the instant the friendship was accepted ({@code friendships.responded_at}).
 */
public record FriendSummary(
    UUID userId, String displayName, String friendCode, Instant friendsSince) {}
