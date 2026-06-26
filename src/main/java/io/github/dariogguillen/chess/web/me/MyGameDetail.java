package io.github.dariogguillen.chess.web.me;

import io.github.dariogguillen.chess.domain.GameResult;
import io.github.dariogguillen.chess.domain.GameStatus;
import io.github.dariogguillen.chess.domain.Side;
import io.github.dariogguillen.chess.web.game.GameStateResponse.MoveSummary;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Wire-format detail of a single archived game for the {@code GET /api/me/games/{id}} response
 * (feature 23.94, {@code game-review}). Unlike {@link MyGameSummary} — which carries only a {@code
 * moveCount} for the list view — this record carries the FULL ordered move list so the frontend can
 * replay the game move-by-move from {@code startingFen} to {@code finalFen}.
 *
 * <p>The {@code selfSide} field is computed at the service boundary by comparing the authenticated
 * user's id against {@code whiteUserId} / {@code blackUserId}; the frontend uses it to orient (and
 * flip, for BLACK) the board. The two display names are the audit-time snapshots frozen on {@code
 * games.{white,black}_display_name} at archive time, so a later user-rename does not rewrite past
 * games.
 *
 * <p>The per-move element reuses {@link MoveSummary} from the live-game response namespace rather
 * than introducing a parallel record: the wire shape is identical (origin/destination square in
 * lowercase algebraic notation plus a nullable promotion piece name), so a single DTO keeps the
 * frontend's replay parser uniform across the live and archived surfaces. See "Decisions taken" in
 * {@code notes/23.94-game-review.md}.
 *
 * @param gameId the archived game's identifier; a UUID serialised as a JSON string.
 * @param roomId the id of the room the game was played in.
 * @param whiteDisplayName the audit-time display name of the white side (snapshot).
 * @param blackDisplayName the audit-time display name of the black side (snapshot).
 * @param selfSide {@link Side#WHITE} or {@link Side#BLACK}, depending on which side the
 *     authenticated caller sat on; lets the UI orient/flip the board.
 * @param status the terminal status of the game.
 * @param result who won the game (feature 23.92, {@code game-result-persistence}); {@code null} for
 *     legacy rows whose winner could not be recovered at backfill time.
 * @param startingFen the FEN the game began from; the replay's first frame.
 * @param finalFen the FEN after the last move; the replay's last frame.
 * @param endedAt the timestamp the game was archived; serialised as ISO-8601 by Jackson.
 * @param moves the full move list in playback order ({@code move_idx} ascending), each as a {@link
 *     MoveSummary}; an empty list for a game that terminated before any move (e.g. a timeout).
 */
public record MyGameDetail(
    @Schema(description = "Archived game id.", example = "0d52a8a0-aaaa-bbbb-cccc-ddddeeee0000")
        UUID gameId,
    @Schema(description = "Room id the game was played in.", example = "K7M3X9") String roomId,
    @Schema(
            description = "Audit-time display name of the white side (frozen at game time).",
            example = "Alice")
        String whiteDisplayName,
    @Schema(
            description = "Audit-time display name of the black side (frozen at game time).",
            example = "Bob")
        String blackDisplayName,
    @Schema(
            description =
                "Side the authenticated caller was on; the UI uses it to orient/flip the board.",
            example = "WHITE")
        Side selfSide,
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
    @Schema(
            description = "FEN the game started from; the first frame of the replay.",
            example = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
        String startingFen,
    @Schema(
            description = "FEN after the last move; the final frame of the replay.",
            example = "rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq - 1 3")
        String finalFen,
    @Schema(description = "Instant the game was archived.", example = "2026-05-19T10:23:11.123Z")
        Instant endedAt,
    @Schema(
            description =
                "Full move history in playback order (move_idx ascending). Empty for a game that "
                    + "ended before any move was played.")
        List<MoveSummary> moves) {}
