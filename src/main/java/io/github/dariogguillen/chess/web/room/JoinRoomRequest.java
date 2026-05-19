package io.github.dariogguillen.chess.web.room;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /api/rooms/{id}/join}. Same shape as {@link CreateRoomRequest}: the
 * caller passes their display name and the server assigns a fresh player id. {@code displayName} is
 * required and non-blank; Bean Validation enforces it at the controller boundary.
 *
 * @param displayName the name to display for the joining player; non-blank.
 */
public record JoinRoomRequest(@NotBlank @Schema(example = "Bob") String displayName) {}
