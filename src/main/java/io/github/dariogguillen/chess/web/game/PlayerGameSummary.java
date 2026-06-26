package io.github.dariogguillen.chess.web.game;

import io.github.dariogguillen.chess.domain.GameResult;
import io.github.dariogguillen.chess.domain.GameStatus;
import io.github.dariogguillen.chess.domain.Side;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * Wire-format summary of a single archived game for the {@code GET /api/players/{id}/games}
 * response.
 *
 * <p>The summary intentionally omits the move history — the endpoint is a "list", not a "detail". A
 * future game-detail endpoint (out of scope) would carry the full move list, leveraging the same
 * {@code games} / {@code moves} tables already populated by the archive path.
 *
 * @param gameId the archived game's identifier; a UUID serialised as a JSON string.
 * @param roomId the id of the room the game was played in.
 * @param opponentDisplayName the display name of the other player (the one that is not the player
 *     the history was queried for).
 * @param selfRole {@link Side#WHITE} or {@link Side#BLACK}, depending on which side the queried
 *     player sat on.
 * @param status the terminal status of the game ({@code CHECKMATE}, {@code STALEMATE}, {@code
 *     DRAW}, {@code ABANDONED}, or {@code TIMEOUT}).
 * @param result who won the game (feature 23.92, {@code game-result-persistence}); {@code null} for
 *     legacy rows whose winner could not be recovered at backfill time.
 * @param endedAt the timestamp the game was archived; serialised as ISO-8601 by Jackson.
 * @param moveCount the number of moves played in the game.
 */
public record PlayerGameSummary(
    UUID gameId,
    String roomId,
    String opponentDisplayName,
    @Schema(description = "Side the queried player was on.", example = "WHITE") Side selfRole,
    @Schema(
            description = "Terminal status of the game.",
            example = "CHECKMATE",
            allowableValues = {"CHECKMATE", "STALEMATE", "DRAW", "ABANDONED", "TIMEOUT"})
        GameStatus status,
    @Schema(
            description =
                "Who won the game. WHITE_WIN / BLACK_WIN / DRAW. Null for legacy archived games "
                    + "whose winner was unknown at backfill time.",
            example = "WHITE_WIN",
            nullable = true,
            allowableValues = {"WHITE_WIN", "BLACK_WIN", "DRAW"})
        GameResult result,
    @Schema(description = "Instant the game was archived.", example = "2026-05-19T10:23:11.123Z")
        Instant endedAt,
    @Schema(description = "Number of moves played in the game.", example = "42") int moveCount) {}
