package io.github.dariogguillen.chess.web.room;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /api/rooms}. The {@code displayName} becomes the human-readable
 * label of the creator and must not be blank — Bean Validation enforces this at the controller
 * boundary via {@link NotBlank}, and {@code GlobalExceptionHandler} translates a failure into a 400
 * with the {@code VALIDATION_FAILED} error code.
 *
 * @param displayName the name to display for the room creator; non-blank.
 */
public record CreateRoomRequest(@NotBlank @Schema(example = "Alice") String displayName) {}
