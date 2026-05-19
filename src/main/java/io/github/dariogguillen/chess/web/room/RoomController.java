package io.github.dariogguillen.chess.web.room;

import io.github.dariogguillen.chess.exception.ErrorResponse;
import io.github.dariogguillen.chess.service.RoomService;
import io.github.dariogguillen.chess.service.RoomService.CreatedRoom;
import io.github.dariogguillen.chess.service.RoomService.JoinedRoom;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for the room lifecycle. Two operations: create a room (one-player) and join an
 * existing room (becomes the second player, also triggers game creation).
 *
 * <p>The controller is a thin translation layer: HTTP in, {@link RoomService} call, {@link
 * RoomResponse} out. No try/catch — exceptions thrown by the service surface through the global
 * {@code @RestControllerAdvice} as structured error responses.
 */
@Tag(name = "Rooms", description = "Create and join chess rooms.")
@RestController
@RequestMapping("/api/rooms")
public class RoomController {

  private static final String ROLE_WHITE = "WHITE";
  private static final String ROLE_BLACK = "BLACK";

  private final RoomService roomService;

  public RoomController(RoomService roomService) {
    this.roomService = roomService;
  }

  @Operation(
      summary = "Create a room",
      description =
          "Creates a new room with the caller as White. The response includes the assigned "
              + "playerId and the freshly generated roomId.")
  @ApiResponse(
      responseCode = "201",
      description = "Room created",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = RoomResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description = "Invalid request (validation failure or malformed JSON)",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public RoomResponse createRoom(@Valid @RequestBody CreateRoomRequest request) {
    CreatedRoom created = roomService.createRoom(request.displayName());
    return new RoomResponse(created.room().id(), created.creator().id(), ROLE_WHITE, null);
  }

  @Operation(
      summary = "Join a room",
      description =
          "Joins {id} as the second player (Black) and creates the game in the same atomic "
              + "step. Path {id} is case-insensitive; the canonical uppercase form is returned "
              + "in the body.")
  @ApiResponse(
      responseCode = "200",
      description = "Joined; game created",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = RoomResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description = "Invalid request (validation failure or malformed JSON)",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description = "Room does not exist",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "409",
      description = "Room already has two players",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @PostMapping("/{id}/join")
  public RoomResponse joinRoom(
      @PathVariable("id") String id, @Valid @RequestBody JoinRoomRequest request) {
    JoinedRoom joined = roomService.joinRoom(id.toUpperCase(Locale.ROOT), request.displayName());
    return new RoomResponse(
        joined.room().id(), joined.joiner().id(), ROLE_BLACK, joined.game().id());
  }
}
