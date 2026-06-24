package io.github.dariogguillen.chess.web.me;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response body for {@code GET /api/me/friend-code} (feature 23.8). Carries the authenticated
 * user's shareable friend code — the value another user types to send a friend request. Kept
 * isolated from feature 16's {@code MeResponse} so adding the friend code did not change the {@code
 * /api/me} contract.
 *
 * @param friendCode the caller's 8-char shareable code.
 */
public record FriendCodeResponse(
    @Schema(
            description =
                "The caller's shareable friend code; 8 chars over an unambiguous alphabet.",
            example = "K7M3X9PQ")
        String friendCode) {}
