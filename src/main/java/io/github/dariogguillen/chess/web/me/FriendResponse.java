package io.github.dariogguillen.chess.web.me;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * Wire-format summary of one accepted friend for the {@code GET /api/me/friends} response (feature
 * 23.8). The {@code displayName} and {@code friendCode} are the friend's <em>current</em> values,
 * resolved by an entity join at query time (not a snapshot), so a friend's rename is reflected
 * here.
 *
 * @param userId the friend's user id.
 * @param displayName the friend's current display name.
 * @param friendCode the friend's shareable code.
 * @param friendsSince the instant the friendship was accepted.
 */
public record FriendResponse(
    @Schema(description = "The friend's user id.", example = "0d52a8a0-aaaa-bbbb-cccc-ddddeeee0000")
        UUID userId,
    @Schema(
            description = "The friend's current display name (live, not a snapshot).",
            example = "Bob")
        String displayName,
    @Schema(description = "The friend's shareable friend code.", example = "K7M3X9PQ")
        String friendCode,
    @Schema(
            description = "Instant the friendship was accepted.",
            example = "2026-06-23T10:23:11.123Z")
        Instant friendsSince) {}
