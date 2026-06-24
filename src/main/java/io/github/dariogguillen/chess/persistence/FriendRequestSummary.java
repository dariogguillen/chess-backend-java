package io.github.dariogguillen.chess.persistence;

import java.time.Instant;
import java.util.UUID;

/**
 * JPQL constructor-projection of one PENDING friend request, used by {@link
 * FriendshipRepository#findIncomingRequests(UUID, org.springframework.data.domain.Pageable)} and
 * {@link FriendshipRepository#findOutgoingRequests(UUID, org.springframework.data.domain.Pageable)}
 * to back {@code GET /api/me/friends/requests/incoming} and {@code .../outgoing}.
 *
 * <p>Carries the request's own id (so the client can call accept/reject/cancel on it) plus the
 * OTHER user's id and <em>live</em> display name (entity-joined, same rename-reflecting choice as
 * {@link FriendSummary}). For the incoming list the other user is the requester; for the outgoing
 * list it is the addressee — each query joins the appropriate side.
 *
 * @param requestId the friendship row's id, used as the path variable for accept/reject/cancel.
 * @param userId the other party's user id.
 * @param displayName the other party's current display name (live, via entity join).
 * @param friendCode the other party's shareable code.
 * @param createdAt the instant the request was created ({@code friendships.created_at}).
 */
public record FriendRequestSummary(
    UUID requestId, UUID userId, String displayName, String friendCode, Instant createdAt) {}
