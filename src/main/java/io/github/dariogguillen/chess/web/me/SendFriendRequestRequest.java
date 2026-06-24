package io.github.dariogguillen.chess.web.me;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /api/me/friends/requests} (feature 23.8). The caller addresses a
 * friend request to whoever owns this code.
 *
 * @param friendCode the addressee's shareable friend code; non-blank.
 */
public record SendFriendRequestRequest(
    @Schema(description = "The friend code of the user to send a request to.", example = "K7M3X9PQ")
        @NotBlank
        String friendCode) {}
