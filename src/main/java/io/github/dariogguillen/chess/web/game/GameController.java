package io.github.dariogguillen.chess.web.game;

import io.github.dariogguillen.chess.domain.Game;
import io.github.dariogguillen.chess.domain.Move;
import io.github.dariogguillen.chess.domain.Piece;
import io.github.dariogguillen.chess.domain.Player;
import io.github.dariogguillen.chess.domain.Side;
import io.github.dariogguillen.chess.domain.Square;
import io.github.dariogguillen.chess.exception.ErrorResponse;
import io.github.dariogguillen.chess.service.GameService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for game state and move application. Two operations: read the current state and
 * apply a move on behalf of one of the two players.
 *
 * <p>The controller is a thin translation layer: HTTP in, {@link GameService} call, {@link
 * GameStateResponse} out. No try/catch — exceptions thrown by the service surface through the
 * global {@code @RestControllerAdvice} as structured error responses.
 *
 * <p>{@code id} and {@code X-Player-Id} are bound as {@link UUID}. Spring's default {@code
 * String→UUID} converter parses them; a malformed value triggers {@code
 * MethodArgumentTypeMismatchException}, which the global handler maps to {@code 400
 * MALFORMED_REQUEST}.
 */
@Tag(name = "Games", description = "Make moves and read game state.")
@RestController
@RequestMapping("/api/games")
public class GameController {

  private final GameService gameService;

  public GameController(GameService gameService) {
    this.gameService = gameService;
  }

  @Operation(
      summary = "Read game state",
      description =
          "Returns the current state of the game: position FEN, status, whose turn it is, the "
              + "two players, and the full move history.")
  @ApiResponse(
      responseCode = "200",
      description = "Current game state",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = GameStateResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description = "Invalid request (malformed UUID in path)",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description = "Game does not exist",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @GetMapping("/{id}")
  public GameStateResponse get(@PathVariable("id") UUID id) {
    return toResponse(gameService.findById(id));
  }

  @Operation(
      summary = "Apply a move",
      description =
          "Applies a move on behalf of the caller (identified by the X-Player-Id header). "
              + "The move is rejected if it is not the caller's turn, if the move is illegal in "
              + "the current position, or if the game has already ended.")
  @ApiResponse(
      responseCode = "200",
      description = "Move applied",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = GameStateResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description =
          "Invalid request (validation failure on body fields, malformed JSON body, malformed "
              + "UUID in path or header, or missing X-Player-Id header)",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description = "Game does not exist",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "409",
      description = "Game has already ended",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "422",
      description = "Move is illegal or not the caller's turn",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @PostMapping("/{id}/moves")
  public GameStateResponse move(
      @PathVariable("id") UUID id,
      @RequestHeader("X-Player-Id") UUID playerId,
      @Valid @RequestBody MoveRequest request) {
    Move move =
        new Move(
            new Square(request.from()),
            new Square(request.to()),
            request.promotion() == null
                ? Optional.empty()
                : Optional.of(Piece.valueOf(request.promotion())));
    return toResponse(gameService.applyMove(id, playerId, move));
  }

  /** Maps a domain {@link Game} to the wire-format {@link GameStateResponse}. */
  private static GameStateResponse toResponse(Game game) {
    Side turn = game.moves().size() % 2 == 0 ? Side.WHITE : Side.BLACK;
    return new GameStateResponse(
        game.id(),
        game.roomId(),
        toPlayerView(game.white()),
        toPlayerView(game.black()),
        game.fen(),
        game.status(),
        turn,
        game.moves().stream().map(GameController::toMoveSummary).toList());
  }

  /**
   * Strips the auth-bearing {@code userId} field at the wire boundary: maps a domain {@link Player}
   * to {@link GameStateResponse.PlayerView} carrying only {@code (id, displayName)}. See {@link
   * GameStateResponse.PlayerView}'s JavaDoc for the rationale.
   */
  private static GameStateResponse.PlayerView toPlayerView(Player player) {
    return new GameStateResponse.PlayerView(player.id(), player.displayName());
  }

  /** Maps a domain {@link Move} to the wire-format {@link GameStateResponse.MoveSummary}. */
  private static GameStateResponse.MoveSummary toMoveSummary(Move move) {
    return new GameStateResponse.MoveSummary(
        move.from().value(), move.to().value(), move.promotion().map(Enum::name).orElse(null));
  }
}
