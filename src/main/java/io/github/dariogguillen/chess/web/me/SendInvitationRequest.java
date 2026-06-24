package io.github.dariogguillen.chess.web.me;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Request body for {@code POST /api/me/invitations} (feature 23.9, {@code direct-invitations}): the
 * caller invites {@code friendUserId} to the FRIEND room {@code roomId} they already created.
 *
 * @param roomId the id of the room the caller created and is inviting into; the caller must be a
 *     player of it. A 6-char short code.
 * @param friendUserId the user id of an ACCEPTED friend of the caller, from their friends list.
 */
public record SendInvitationRequest(
    @Schema(
            description =
                "Id of the FRIEND room the caller created and is inviting into. The caller must be "
                    + "a player of this room. Six-char short code.",
            example = "K7M3X9")
        @NotBlank
        String roomId,
    @Schema(
            description =
                "User id of an ACCEPTED friend of the caller (from GET /api/me/friends). Must be an "
                    + "existing accepted friendship or the request is rejected 404 FRIEND_NOT_FOUND.",
            example = "8b3c1f04-1234-5678-9abc-def012345678")
        @NotNull
        UUID friendUserId) {}
