package io.github.dariogguillen.chess.web.me;

import io.github.dariogguillen.chess.domain.GameResult;
import io.github.dariogguillen.chess.domain.GameStatus;
import io.github.dariogguillen.chess.domain.Side;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * Wire-format summary of a single archived game for the {@code GET /api/me/games} response. Mirrors
 * {@link io.github.dariogguillen.chess.web.game.PlayerGameSummary} in shape but is keyed by the
 * authenticated user (via {@code white_user_id} / {@code black_user_id}) rather than the per-
 * session player id — see "Decisions taken" in {@code notes/19-auth-my-games.md} for why both
 * endpoints coexist.
 *
 * <p>The {@code selfSide} field is computed at the controller boundary by comparing the
 * authenticated user's id against {@code whiteUserId} / {@code blackUserId} from the JPQL
 * projection; {@code opponentDisplayName} is the audit-time display name of the OTHER side, frozen
 * at game time on {@code games.{white,black}_display_name} so a future rename on the {@code users}
 * row does not retroactively rewrite history.
 *
 * @param gameId the archived game's identifier; a UUID serialised as a JSON string.
 * @param roomId the id of the room the game was played in.
 * @param opponentDisplayName the display name of the other player at game time (snapshot).
 * @param selfSide {@link Side#WHITE} or {@link Side#BLACK}, depending on which side the
 *     authenticated user sat on.
 * @param status the terminal status of the game.
 * @param result who won the game (feature 23.92, {@code game-result-persistence}); {@code null} for
 *     legacy rows whose winner could not be recovered at backfill time.
 * @param endedAt the timestamp the game was archived; serialised as ISO-8601 by Jackson.
 * @param moveCount the number of moves played in the game.
 */
public record MyGameSummary(
    @Schema(description = "Archived game id.", example = "0d52a8a0-aaaa-bbbb-cccc-ddddeeee0000")
        UUID gameId,
    @Schema(description = "Room id the game was played in.", example = "K7M3X9") String roomId,
    @Schema(
            description =
                "Audit-time display name of the opponent (frozen at game time; a later "
                    + "user-rename does not rewrite past games).",
            example = "Bob")
        String opponentDisplayName,
    @Schema(description = "Side the authenticated user was on.", example = "WHITE") Side selfSide,
    @Schema(
            description = "Terminal status of the game.",
            example = "CHECKMATE",
            allowableValues = {"CHECKMATE", "STALEMATE", "DRAW", "ABANDONED", "TIMEOUT"})
        GameStatus status,
    @Schema(
            description =
                "Who won the game. WHITE_WIN / BLACK_WIN / DRAW. Null for legacy archived games "
                    + "whose winner was unknown at backfill time (old ABANDONED rows do not encode "
                    + "the abandoner in the final FEN).",
            example = "WHITE_WIN",
            nullable = true,
            allowableValues = {"WHITE_WIN", "BLACK_WIN", "DRAW"})
        GameResult result,
    @Schema(description = "Instant the game was archived.", example = "2026-05-19T10:23:11.123Z")
        Instant endedAt,
    @Schema(description = "Number of moves played in the game.", example = "42") int moveCount) {}
