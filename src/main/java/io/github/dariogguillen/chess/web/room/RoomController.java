package io.github.dariogguillen.chess.web.room;

import io.github.dariogguillen.chess.domain.Room;
import io.github.dariogguillen.chess.domain.Side;
import io.github.dariogguillen.chess.domain.User;
import io.github.dariogguillen.chess.exception.ErrorResponse;
import io.github.dariogguillen.chess.exception.RoomNotFoundException;
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
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for the room lifecycle. Three operations: create a room (one-player), join an
 * existing room (becomes the second player, also triggers game creation), and read the current
 * state of a room (the discovery / fallback path documented in {@code docs/architecture.md} → "Room
 * lifecycle").
 *
 * <p>The controller is a thin translation layer: HTTP in, {@link RoomService} call, response out.
 * No try/catch — exceptions thrown by the service surface through the global
 * {@code @RestControllerAdvice} as structured error responses.
 */
@Tag(name = "Rooms", description = "Create, join, and read chess rooms.")
@RestController
@RequestMapping("/api/rooms")
public class RoomController {

  private final RoomService roomService;
  private final RoomDetailsMapper roomDetailsMapper;

  public RoomController(RoomService roomService, RoomDetailsMapper roomDetailsMapper) {
    this.roomService = roomService;
    this.roomDetailsMapper = roomDetailsMapper;
  }

  @Operation(
      summary = "Create a room",
      description =
          "Creates a new room with the caller as its single player. The caller's side comes from "
              + "the optional preferredSide field (WHITE/BLACK/RANDOM; omitted defaults to WHITE; "
              + "RANDOM is coin-flipped server-side). The response role reflects the resolved "
              + "side, alongside the assigned playerId and the freshly generated roomId. The "
              + "response also carries a secret joinToken (only the creator obtains it) that the "
              + "opponent must supply to POST /api/rooms/{id}/join.")
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
  public RoomResponse createRoom(
      @Valid @RequestBody CreateRoomRequest request, @AuthenticationPrincipal User currentUser) {
    UUID currentUserId = currentUser == null ? null : currentUser.getId();
    CreatedRoom created =
        roomService.createRoom(
            request.displayName(), request.preferredSide(), currentUserId, request.timeControl());
    String role = created.room().creatorSide().name();
    return new RoomResponse(
        created.room().id(), created.creator().id(), role, null, created.room().joinToken());
  }

  @Operation(
      summary = "Join a room",
      description =
          "Joins {id} as the second player and creates the game in the same atomic step. Requires "
              + "the secret joinToken the creator obtained from the create response; a missing or "
              + "wrong token returns 403 INVALID_JOIN_TOKEN (the roomId alone, used for watching, "
              + "does not authorise joining). The joiner's role is the opposite of the side the "
              + "creator chose at create time. Path {id} is case-insensitive; the canonical "
              + "uppercase form is returned in the body.")
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
      responseCode = "403",
      description = "Missing or invalid join token for a token-protected room",
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
      @PathVariable("id") String id,
      @Valid @RequestBody JoinRoomRequest request,
      @AuthenticationPrincipal User currentUser) {
    UUID currentUserId = currentUser == null ? null : currentUser.getId();
    JoinedRoom joined =
        roomService.joinRoom(
            id.toUpperCase(Locale.ROOT), request.displayName(), currentUserId, request.joinToken());
    Side joinerSide = opposite(joined.room().creatorSide());
    // The joiner is already in the room, so they never need the token: it is deliberately left
    // null on the join response (the create response is the only place the real token surfaces).
    return new RoomResponse(
        joined.room().id(), joined.joiner().id(), joinerSide.name(), joined.game().id(), null);
  }

  private static Side opposite(Side side) {
    return side == Side.WHITE ? Side.BLACK : Side.WHITE;
  }

  @Operation(
      summary = "Get room state by id",
      description =
          "Reads the current state of a room: the players (with roles derived from the creator's "
              + "chosen side), "
              + "the associated gameId if the room is ACTIVE, and the lifecycle status. The "
              + "frontend uses this either as the primary discovery mechanism for Player A "
              + "(poll until gameId is non-null) or as a fallback to STOMP "
              + "/topic/rooms/{roomId} for late subscribers, which cannot replay events. Path "
              + "{id} is case-insensitive; the canonical uppercase form is returned in the body.")
  @ApiResponse(
      responseCode = "200",
      description = "Room exists; returns its current state",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = RoomDetailsResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description = "Room does not exist",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @GetMapping("/{id}")
  public RoomDetailsResponse getRoom(@PathVariable("id") String id) {
    String canonicalId = id.toUpperCase(Locale.ROOT);
    Room room =
        roomService.findById(canonicalId).orElseThrow(() -> new RoomNotFoundException(canonicalId));
    Optional<UUID> gameId = roomService.findGameIdByRoomId(canonicalId);
    return roomDetailsMapper.toResponse(room, gameId);
  }
}
