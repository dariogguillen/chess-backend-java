package io.github.dariogguillen.chess.web.me;

import io.github.dariogguillen.chess.domain.Side;
import io.github.dariogguillen.chess.domain.User;
import io.github.dariogguillen.chess.exception.ErrorResponse;
import io.github.dariogguillen.chess.service.InvitationService;
import io.github.dariogguillen.chess.service.InvitationService.LiveInvitation;
import io.github.dariogguillen.chess.service.RoomService.JoinedRoom;
import io.github.dariogguillen.chess.web.room.RoomResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for direct invitations (feature 23.9). All routes live under {@code /api/me} and
 * require a valid Bearer JWT — Spring Security 401s every unauthenticated call before any handler
 * runs, so {@code @AuthenticationPrincipal User caller} is non-null inside each method.
 *
 * <p>The lifecycle: send (inviter → friend), list incoming (invitee), accept (invitee, performs the
 * room join server-side), decline (invitee), cancel (inviter). The controller is routing-only;
 * {@link InvitationService} owns the rules and {@code GlobalExceptionHandler} maps the typed
 * exceptions to their HTTP statuses.
 *
 * <p>The accept response reuses {@link RoomResponse} (the same body {@code POST
 * /api/rooms/{id}/join} returns) so a client that already understands the join shape needs no new
 * model. The {@code joinToken} field is always {@code null} on this path — the join already
 * happened server-side and the invitee never needs the token.
 */
@Tag(name = "Invitations", description = "Invite accepted friends to a room you created.")
@RestController
@RequestMapping("/api/me/invitations")
public class InvitationController {

  private final InvitationService invitationService;

  public InvitationController(InvitationService invitationService) {
    this.invitationService = invitationService;
  }

  @Operation(
      summary = "Invite a friend to a room",
      description =
          "Invites an ACCEPTED friend (by user id) to a FRIEND room the caller created. The invitee "
              + "receives a real-time push on /user/queue/invitations if connected, and can also "
              + "fetch it via GET /api/me/invitations. Re-sending the same (room, invitee) is "
              + "idempotent (refresh, no error). Requires a valid Bearer JWT.")
  @ApiResponse(responseCode = "201", description = "Invitation sent.")
  @ApiResponse(
      responseCode = "400",
      description = "Invalid request (validation failure or malformed JSON).",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Missing or invalid JWT.",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "403",
      description = "NOT_ROOM_MEMBER — the caller is not a player of the room.",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description =
          "FRIEND_NOT_FOUND — friendUserId is not an accepted friend; or ROOM_NOT_FOUND — the room "
              + "does not exist.",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "409",
      description = "ROOM_FULL — the room has no free joinable slot (already full, or a bot room).",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public void sendInvitation(
      @AuthenticationPrincipal User caller, @Valid @RequestBody SendInvitationRequest body) {
    invitationService.send(
        caller.getId(),
        caller.getDisplayName(),
        body.roomId().toUpperCase(Locale.ROOT),
        body.friendUserId());
  }

  @Operation(
      summary = "List incoming invitations",
      description =
          "Returns the caller's pending incoming invitations, filtered to still-live, still-joinable "
              + "rooms (stale ones are pruned). Each entry carries the side the invitee would take "
              + "and the room's time control, derived from the live room. Requires a valid Bearer "
              + "JWT.")
  @ApiResponse(
      responseCode = "200",
      description = "The caller's live incoming invitations (possibly empty).",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = InvitationResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Missing or invalid JWT.",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @GetMapping
  public List<InvitationResponse> listInvitations(@AuthenticationPrincipal User caller) {
    return invitationService.listIncoming(caller.getId()).stream()
        .map(InvitationController::toResponse)
        .toList();
  }

  @Operation(
      summary = "Accept an invitation",
      description =
          "Accepts the invitation for {roomId}, joining the room as the second player. The join is "
              + "performed server-side using the room's stored join token (never exposed to the "
              + "client); the inviter learns of the join via the existing RoomJoinedEvent on "
              + "/topic/rooms/{roomId}. Returns the joined room (roomId, playerId, role, gameId). "
              + "Path {roomId} is case-insensitive. Requires a valid Bearer JWT.")
  @ApiResponse(
      responseCode = "200",
      description = "Joined; game created.",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = RoomResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Missing or invalid JWT.",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description = "INVITATION_NOT_FOUND — no live invitation for the caller on that room.",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "409",
      description = "ROOM_FULL — the room filled before the caller accepted.",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @PostMapping("/{roomId}/accept")
  public RoomResponse acceptInvitation(
      @AuthenticationPrincipal User caller, @PathVariable("roomId") String roomId) {
    JoinedRoom joined =
        invitationService.accept(
            caller.getId(), caller.getDisplayName(), roomId.toUpperCase(Locale.ROOT));
    Side joinerSide = opposite(joined.room().creatorSide());
    return new RoomResponse(
        joined.room().id(), joined.joiner().id(), joinerSide.name(), joined.game().id(), null);
  }

  @Operation(
      summary = "Decline an invitation",
      description =
          "The invitee declines the invitation for {roomId}; it is deleted and the inviter receives "
              + "an InvitationDeclinedEvent on their user queue. Path {roomId} is case-insensitive. "
              + "Requires a valid Bearer JWT.")
  @ApiResponse(responseCode = "204", description = "Invitation declined.")
  @ApiResponse(
      responseCode = "401",
      description = "Missing or invalid JWT.",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description = "INVITATION_NOT_FOUND — no invitation for the caller on that room.",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @DeleteMapping("/{roomId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void declineInvitation(
      @AuthenticationPrincipal User caller, @PathVariable("roomId") String roomId) {
    invitationService.decline(caller.getId(), roomId.toUpperCase(Locale.ROOT));
  }

  @Operation(
      summary = "Cancel an invitation",
      description =
          "The inviter cancels an invitation they sent to {inviteeUserId} for {roomId}; it is "
              + "deleted and the invitee receives an InvitationCancelledEvent on their user queue. "
              + "Path {roomId} is case-insensitive. Requires a valid Bearer JWT.")
  @ApiResponse(responseCode = "204", description = "Invitation cancelled.")
  @ApiResponse(
      responseCode = "401",
      description = "Missing or invalid JWT.",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "403",
      description = "NOT_ROOM_MEMBER — the caller is not a player of the room.",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description = "INVITATION_NOT_FOUND — no such invitation.",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @DeleteMapping("/{roomId}/to/{inviteeUserId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void cancelInvitation(
      @AuthenticationPrincipal User caller,
      @PathVariable("roomId") String roomId,
      @PathVariable("inviteeUserId") UUID inviteeUserId) {
    invitationService.cancel(caller.getId(), roomId.toUpperCase(Locale.ROOT), inviteeUserId);
  }

  private static InvitationResponse toResponse(LiveInvitation invitation) {
    return new InvitationResponse(
        invitation.roomId(),
        invitation.inviterUserId(),
        invitation.inviterDisplayName(),
        invitation.timeControl(),
        invitation.inviteeSide().name(),
        invitation.createdAt());
  }

  private static Side opposite(Side side) {
    return side == Side.WHITE ? Side.BLACK : Side.WHITE;
  }
}
