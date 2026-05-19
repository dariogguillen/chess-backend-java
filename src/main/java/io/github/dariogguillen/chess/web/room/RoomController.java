package io.github.dariogguillen.chess.web.room;

import io.github.dariogguillen.chess.service.RoomService;
import io.github.dariogguillen.chess.service.RoomService.CreatedRoom;
import io.github.dariogguillen.chess.service.RoomService.JoinedRoom;
import jakarta.validation.Valid;
import java.util.Locale;
import org.springframework.http.HttpStatus;
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
@RestController
@RequestMapping("/api/rooms")
public class RoomController {

  private static final String ROLE_WHITE = "WHITE";
  private static final String ROLE_BLACK = "BLACK";

  private final RoomService roomService;

  public RoomController(RoomService roomService) {
    this.roomService = roomService;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public RoomResponse createRoom(@Valid @RequestBody CreateRoomRequest request) {
    CreatedRoom created = roomService.createRoom(request.displayName());
    return new RoomResponse(created.room().id(), created.creator().id(), ROLE_WHITE, null);
  }

  @PostMapping("/{id}/join")
  public RoomResponse joinRoom(
      @PathVariable("id") String id, @Valid @RequestBody JoinRoomRequest request) {
    JoinedRoom joined = roomService.joinRoom(id.toUpperCase(Locale.ROOT), request.displayName());
    return new RoomResponse(
        joined.room().id(), joined.joiner().id(), ROLE_BLACK, joined.game().id());
  }
}
