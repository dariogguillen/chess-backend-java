package io.github.dariogguillen.chess.web.game;

import io.github.dariogguillen.chess.domain.Game;
import io.github.dariogguillen.chess.domain.GameStatus;
import io.github.dariogguillen.chess.domain.Move;
import io.github.dariogguillen.chess.domain.Player;
import io.github.dariogguillen.chess.domain.Side;
import io.github.dariogguillen.chess.domain.Square;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/**
 * Unified response body for both {@code GET /api/games/{id}} and {@code POST
 * /api/games/{id}/moves}. The shape is identical so that the client only has to know one record.
 *
 * <p>{@code turn} is derived from {@code moves.size() % 2 == 0 ? WHITE : BLACK} at the controller
 * boundary; it is not stored on the domain {@link Game} because it would denormalise the move
 * history. Exposing it spares clients from parsing the FEN.
 *
 * <p>The per-move shape used inside {@code moves} is {@link MoveSummary}, declared as a nested
 * record below. It is intentionally nested because it has no reference site outside this response;
 * outside this file the type is {@code GameStateResponse.MoveSummary}.
 *
 * @param id the game identifier; a UUID serialised as a JSON string.
 * @param roomId the id of the room this game belongs to.
 * @param white the player playing the white pieces.
 * @param black the player playing the black pieces.
 * @param fen the current position in Forsyth-Edwards Notation.
 * @param status the current status of the game.
 * @param turn the side whose turn it is to move.
 * @param moves the move history in playback order, each entry as a {@link MoveSummary}.
 */
public record GameStateResponse(
    UUID id,
    String roomId,
    Player white,
    Player black,
    @Schema(
            description = "Current position in Forsyth-Edwards Notation.",
            example = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1")
        String fen,
    GameStatus status,
    @Schema(
            description = "Side whose turn it is. WHITE if move count is even, BLACK if odd.",
            example = "WHITE")
        Side turn,
    @Schema(description = "Full move history in playback order.") List<MoveSummary> moves) {

  /**
   * Wire-format shape for an individual move inside {@link GameStateResponse#moves()}.
   *
   * <p>This nested record exists because the domain {@link Move} uses {@link Square} (a record
   * around a {@code String}) and {@code Optional<Piece>} for the promotion target. Neither
   * serialises to the simple wire format expected by clients ({@code "e2"} as a plain string,
   * {@code null} as the absent promotion); {@code MoveSummary} provides exactly that shape.
   *
   * @param from origin square in lowercase algebraic notation, e.g. {@code "e2"}.
   * @param to destination square in lowercase algebraic notation, e.g. {@code "e4"}.
   * @param promotion promotion piece name (one of KNIGHT, BISHOP, ROOK, QUEEN) or {@code null} for
   *     non-promotion moves.
   */
  public record MoveSummary(
      @Schema(description = "Origin square in algebraic notation, lowercase.", example = "e2")
          String from,
      @Schema(description = "Destination square in algebraic notation, lowercase.", example = "e4")
          String to,
      @Schema(
              description =
                  "Promotion piece for pawn promotion; one of KNIGHT, BISHOP, ROOK, QUEEN. "
                      + "Null otherwise.",
              example = "QUEEN",
              nullable = true)
          String promotion) {}
}
