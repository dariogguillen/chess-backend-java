package io.github.dariogguillen.chess.web.me;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * Wire-format summary of one pending friend request for the {@code GET
 * /api/me/friends/requests/incoming} and {@code .../outgoing} responses (feature 23.8). The {@code
 * requestId} is the path variable the client uses to accept (incoming only), reject, or cancel. The
 * other party's {@code displayName} is live (entity-joined, not a snapshot).
 *
 * @param requestId the friendship row id, used for accept/reject/cancel.
 * @param userId the other party's user id (the requester for incoming, the addressee for outgoing).
 * @param displayName the other party's current display name.
 * @param friendCode the other party's shareable code.
 * @param createdAt the instant the request was created.
 */
public record FriendRequestResponse(
    @Schema(description = "The request id; used to accept, reject, or cancel.") UUID requestId,
    @Schema(description = "The other party's user id.") UUID userId,
    @Schema(
            description = "The other party's current display name (live, not a snapshot).",
            example = "Alice")
        String displayName,
    @Schema(description = "The other party's shareable friend code.", example = "K7M3X9PQ")
        String friendCode,
    @Schema(description = "Instant the request was created.", example = "2026-06-23T10:23:11.123Z")
        Instant createdAt) {}
