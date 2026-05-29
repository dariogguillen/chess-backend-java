package io.github.dariogguillen.chess.web.room;

import io.github.dariogguillen.chess.domain.SidePreference;
import io.github.dariogguillen.chess.domain.TimeControl;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /api/rooms}. The {@code displayName} becomes the human-readable
 * label of the creator and must not be blank — Bean Validation enforces this at the controller
 * boundary via {@link NotBlank}, and {@code GlobalExceptionHandler} translates a failure into a 400
 * with the {@code VALIDATION_FAILED} error code.
 *
 * <p>{@code preferredSide} (feature 21, `color-selection`) is the creator's requested side. It is
 * optional: a {@code null} / omitted value defaults to {@link SidePreference#WHITE} so existing
 * clients that do not send the field keep the historical "creator is white" behaviour. Being an
 * enum-typed component, springdoc renders its allowable values ({@code WHITE}, {@code BLACK},
 * {@code RANDOM}) into the OpenAPI schema automatically, which {@code openapi-typescript} turns
 * into a literal union on the frontend.
 *
 * <p>{@code timeControl} (feature 22, `time-control`) is the optional declared clock. It is
 * optional: a {@code null} / omitted value creates an untimed room (the historical behaviour), so
 * existing clients that do not send the field keep working unchanged. When present, both sides
 * start at {@code initialMs} and the moving side's clock is decremented server-side on every move,
 * with {@code incrementMs} added back (Fischer). The server auto-flags ({@link
 * io.github.dariogguillen.chess.domain.GameStatus#TIMEOUT}) when the side to move runs out.
 *
 * @param displayName the name to display for the room creator; non-blank.
 * @param preferredSide the creator's requested side; {@code null} → {@link SidePreference#WHITE}.
 * @param timeControl the optional declared clock; {@code null} → untimed room.
 */
public record CreateRoomRequest(
    @NotBlank @Schema(example = "Alice") String displayName,
    @Schema(
            description =
                "The creator's requested side. WHITE or BLACK pin the creator's colour; RANDOM "
                    + "lets the server coin-flip it (the client cannot bias the result). Optional: "
                    + "omitting the field defaults to WHITE.",
            example = "WHITE")
        SidePreference preferredSide,
    @Schema(
            description =
                "Optional time control. When present, both sides start at initialMs and the "
                    + "server tracks the clock authoritatively, auto-flagging (status TIMEOUT) when "
                    + "the side to move runs out. Omit the field for an untimed game.",
            nullable = true)
        TimeControl timeControl) {}
