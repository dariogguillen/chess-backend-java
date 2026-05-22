package io.github.dariogguillen.chess.web.game;

import io.github.dariogguillen.chess.domain.Side;
import io.github.dariogguillen.chess.exception.ErrorResponse;
import io.github.dariogguillen.chess.persistence.ArchivedGamePlayerView;
import io.github.dariogguillen.chess.service.GameHistoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoint for a player's archived game history.
 *
 * <p>Single operation: {@code GET /api/players/{id}/games} returns the player's terminal-status
 * games (newest first, hard-capped at 50). An unknown player id is <em>not</em> a 404 — guests have
 * no registry, and an empty list is the honest answer.
 *
 * <p>The path variable is a {@link UUID}; Spring's default {@code String→UUID} converter parses it
 * and a malformed value surfaces as {@code MethodArgumentTypeMismatchException} → {@code 400
 * MALFORMED_REQUEST} via the global handler.
 *
 * <p>The controller is the thin translation layer: HTTP path id in, {@link GameHistoryService}
 * call, {@link PlayerGameSummary} list out. No try/catch.
 */
@Tag(name = "Players", description = "Player history endpoints.")
@RestController
@RequestMapping("/api/players")
public class PlayerGamesController {

  private final GameHistoryService gameHistoryService;

  public PlayerGamesController(GameHistoryService gameHistoryService) {
    this.gameHistoryService = gameHistoryService;
  }

  @Operation(
      summary = "List a player's game history",
      description =
          "Returns archived (terminal-status) games for the given player, newest first, "
              + "capped at 50 entries. An unknown player id returns 200 with an empty array — "
              + "guests have no registry, so 404 is not used.")
  @ApiResponse(
      responseCode = "200",
      description = "Player game history (possibly empty).",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              array = @ArraySchema(schema = @Schema(implementation = PlayerGameSummary.class))))
  @ApiResponse(
      responseCode = "400",
      description = "Invalid request (malformed UUID in path).",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @GetMapping("/{id}/games")
  public List<PlayerGameSummary> getPlayerGames(@PathVariable("id") UUID id) {
    List<ArchivedGamePlayerView> views = gameHistoryService.findByPlayer(id);
    return views.stream().map(view -> toSummary(view, id)).toList();
  }

  /**
   * Maps an {@link ArchivedGamePlayerView} to a {@link PlayerGameSummary}, deriving {@code
   * selfRole} and {@code opponentDisplayName} from which side of the view the {@code playerId} sits
   * on. The repository query restricts results to games where the player was on exactly one side,
   * so the {@code white} branch is checked first and the {@code black} branch covers the remainder.
   */
  private static PlayerGameSummary toSummary(ArchivedGamePlayerView view, UUID playerId) {
    boolean playerIsWhite = view.whitePlayerId().equals(playerId);
    Side selfRole = playerIsWhite ? Side.WHITE : Side.BLACK;
    String opponentDisplayName = playerIsWhite ? view.blackDisplayName() : view.whiteDisplayName();
    return new PlayerGameSummary(
        view.id(),
        view.roomId(),
        opponentDisplayName,
        selfRole,
        view.status(),
        view.endedAt(),
        view.moveCount());
  }
}
