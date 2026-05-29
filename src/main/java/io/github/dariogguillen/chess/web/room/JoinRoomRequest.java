package io.github.dariogguillen.chess.web.room;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /api/rooms/{id}/join}. The caller passes their display name and the
 * server assigns a fresh player id. {@code displayName} is required and non-blank; Bean Validation
 * enforces it at the controller boundary.
 *
 * <p>{@code joinToken} (feature 22.7, `room-access-tokens`) is the secret the creator obtained from
 * the create response. It is intentionally <strong>not</strong> {@code @NotBlank}: whether a token
 * is required is a business decision made in {@code RoomService.joinRoom} (a legacy / unprotected
 * room created before the feature shipped accepts a token-less join), so a missing token surfaces
 * as a 403 {@code INVALID_JOIN_TOKEN} from the service rather than a 400 validation failure here.
 *
 * @param displayName the name to display for the joining player; non-blank.
 * @param joinToken the secret required to join a token-protected room; nullable.
 */
public record JoinRoomRequest(
    @NotBlank @Schema(example = "Bob") String displayName,
    @Schema(
            description =
                "Secret join token obtained by the creator from the create response and shared "
                    + "out-of-band with the opponent. Required for rooms created after feature "
                    + "22.7 shipped; a missing or wrong token returns 403 INVALID_JOIN_TOKEN.",
            example = "8b3c1f04-1234-5678-9abc-def012345678",
            nullable = true)
        String joinToken) {}
