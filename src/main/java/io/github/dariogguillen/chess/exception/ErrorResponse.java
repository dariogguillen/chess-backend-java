package io.github.dariogguillen.chess.exception;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * Structured JSON body produced by {@link GlobalExceptionHandler} for every error response. Shared
 * by every controller in the application so clients see the same shape regardless of which endpoint
 * produced it.
 *
 * @param error a stable upper-snake-case code identifying the error class (e.g. {@code
 *     ROOM_NOT_FOUND}, {@code VALIDATION_FAILED}); intended for programmatic matching.
 * @param message a human-readable explanation; intended for diagnostics and log surfacing, not for
 *     programmatic matching.
 * @param timestamp the instant the response was produced, in UTC; serialized as ISO-8601 by
 *     Jackson's {@code JavaTimeModule}.
 */
@Schema(
    name = "ErrorResponse",
    description = "Standard error envelope returned by every 4xx response from the API.")
public record ErrorResponse(
    @Schema(
            description = "Stable upper-snake-case error code; intended for programmatic matching.",
            example = "ROOM_NOT_FOUND")
        String error,
    String message,
    Instant timestamp) {}
