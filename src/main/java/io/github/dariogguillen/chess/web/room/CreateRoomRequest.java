package io.github.dariogguillen.chess.web.room;

import io.github.dariogguillen.chess.domain.OpponentKind;
import io.github.dariogguillen.chess.domain.SidePreference;
import io.github.dariogguillen.chess.domain.TimeControl;
import io.github.dariogguillen.chess.service.bot.BotEngine;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
 * <p>{@code opponentKind} (feature 23, `bot-opponent`) selects the opponent. It is optional: a
 * {@code null} / omitted value means {@link OpponentKind#FRIEND} (the historical two-human flow),
 * so existing clients keep working unchanged. {@link OpponentKind#BOT} makes the backend build a
 * complete vs-Stockfish game immediately — the create response then carries a non-null {@code
 * gameId} and no {@code joinToken} (there is no human to invite). Being an enum, springdoc renders
 * the allowable values into the OpenAPI schema for the typed frontend client.
 *
 * <p>{@code botElo} (feature 23.5, `bot-difficulty`) is the requested bot strength, relevant only
 * for {@link OpponentKind#BOT}. It is optional: a {@code null} / omitted value falls back to the
 * configured {@code chess.bot.default-elo} so existing clients keep working unchanged. When present
 * it must lie within Stockfish's {@code UCI_Elo} range ({@link BotEngine#MIN_BOT_ELO}–{@link
 * BotEngine#MAX_BOT_ELO}); Bean Validation ({@link Min}/{@link Max}) rejects an out-of-range value
 * as a 400 {@code VALIDATION_FAILED} at the controller boundary (no new error code). The bound
 * literals come from the same {@link BotEngine} constants that validate the default, so the two
 * cannot drift.
 *
 * @param displayName the name to display for the room creator; non-blank.
 * @param preferredSide the creator's requested side; {@code null} → {@link SidePreference#WHITE}.
 * @param timeControl the optional declared clock; {@code null} → untimed room.
 * @param opponentKind the opponent kind; {@code null} → {@link OpponentKind#FRIEND}.
 * @param botElo the requested bot strength for a BOT room; {@code null} → the configured default;
 *     out of {@link BotEngine#MIN_BOT_ELO}–{@link BotEngine#MAX_BOT_ELO} → 400.
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
        TimeControl timeControl,
    @Schema(
            description =
                "The opponent kind. FRIEND (the default when omitted) creates a room another human "
                    + "joins; BOT creates a complete game against the Stockfish engine immediately, "
                    + "so the response carries a non-null gameId and no joinToken.",
            example = "FRIEND",
            nullable = true)
        OpponentKind opponentKind,
    @Min(BotEngine.MIN_BOT_ELO)
        @Max(BotEngine.MAX_BOT_ELO)
        @Schema(
            description =
                "Requested bot strength as a target Elo, relevant only when opponentKind=BOT. Must "
                    + "lie within Stockfish's UCI_Elo range ("
                    + BotEngine.MIN_BOT_ELO
                    + "-"
                    + BotEngine.MAX_BOT_ELO
                    + "). Omit the field to use the server's configured default strength.",
            minimum = "" + BotEngine.MIN_BOT_ELO,
            maximum = "" + BotEngine.MAX_BOT_ELO,
            example = "1500",
            nullable = true)
        Integer botElo) {}
