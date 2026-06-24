package io.github.dariogguillen.chess.web.me;

import io.github.dariogguillen.chess.domain.User;
import io.github.dariogguillen.chess.exception.ErrorResponse;
import io.github.dariogguillen.chess.persistence.FriendRequestSummary;
import io.github.dariogguillen.chess.persistence.FriendSummary;
import io.github.dariogguillen.chess.service.FriendshipService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for the friends-list feature (feature 23.8). All routes live under {@code /api/me}
 * and require a valid Bearer JWT — Spring Security's filter chain 401s every unauthenticated call
 * (the {@code anyRequest().authenticated()} rule in {@code SecurityConfig}) before any method here
 * runs, so {@code @AuthenticationPrincipal User caller} is non-null inside every handler.
 *
 * <p>The lifecycle is symmetric request/accept: send a request by friend code, accept it (addressee
 * only), reject/cancel it (either participant, deletes the row), remove an accepted friend, and
 * read the three paginated lists (friends, incoming requests, outgoing requests). The controller is
 * routing-only; {@link FriendshipService} owns the rules and {@code GlobalExceptionHandler} maps
 * the typed exceptions to their HTTP statuses.
 *
 * <p>{@link Validated} on the class enables JSR-380 constraints on the {@code page} / {@code size}
 * {@code @RequestParam}s (mirrors {@code MyGamesController}); without it the parameter-level
 * {@code @Min} / {@code @Max} would be silently ignored.
 */
@Tag(name = "Friends", description = "Mutual request/accept friendships discovered by friend code.")
@RestController
@RequestMapping("/api/me")
@Validated
public class FriendshipController {

  /**
   * Hard cap on the page size for all friends/requests lists. Mirrors {@code MyGamesController}.
   */
  static final int MAX_PAGE_SIZE = 100;

  private final FriendshipService friendshipService;

  public FriendshipController(FriendshipService friendshipService) {
    this.friendshipService = friendshipService;
  }

  @Operation(
      summary = "Get the authenticated user's friend code",
      description =
          "Returns the caller's stable, shareable 8-char friend code. Another user adds the caller "
              + "as a friend by typing this code — there is no directory or search surface. "
              + "Requires a valid Bearer JWT.")
  @ApiResponse(
      responseCode = "200",
      description = "The caller's friend code.",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = FriendCodeResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Missing, malformed, expired, or unsigned-by-us JWT.",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @GetMapping("/friend-code")
  public FriendCodeResponse getFriendCode(@AuthenticationPrincipal User caller) {
    return new FriendCodeResponse(caller.getFriendCode());
  }

  @Operation(
      summary = "Send a friend request by friend code",
      description =
          "Creates a PENDING friend request from the caller to the user owning the supplied friend "
              + "code. Requires a valid Bearer JWT.")
  @ApiResponse(responseCode = "201", description = "Request created.")
  @ApiResponse(
      responseCode = "400",
      description = "Blank friend code (validation) or unparseable body.",
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
      responseCode = "404",
      description = "FRIEND_CODE_NOT_FOUND — no user owns the supplied code.",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "409",
      description =
          "ALREADY_FRIENDS (already an accepted friendship) or DUPLICATE_FRIEND_REQUEST (a pending "
              + "request already exists in either direction).",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "422",
      description = "SELF_FRIENDSHIP — the code is the caller's own.",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @PostMapping("/friends/requests")
  @ResponseStatus(HttpStatus.CREATED)
  public void sendRequest(
      @AuthenticationPrincipal User caller, @Valid @RequestBody SendFriendRequestRequest body) {
    friendshipService.sendRequest(caller.getId(), body.friendCode());
  }

  @Operation(
      summary = "Accept an incoming friend request",
      description =
          "Flips a PENDING request to ACCEPTED. Only the request's addressee may accept; any other "
              + "caller — including the requester or one naming an unknown id — gets 404 with the "
              + "same FRIEND_REQUEST_NOT_FOUND code (no existence leak). Requires a valid Bearer JWT.")
  @ApiResponse(responseCode = "204", description = "Request accepted.")
  @ApiResponse(
      responseCode = "401",
      description = "Missing or invalid JWT.",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description =
          "FRIEND_REQUEST_NOT_FOUND — the request does not exist or the caller is not its addressee.",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @PostMapping("/friends/requests/{id}/accept")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void acceptRequest(@AuthenticationPrincipal User caller, @PathVariable UUID id) {
    friendshipService.acceptRequest(id, caller.getId());
  }

  @Operation(
      summary = "Reject or cancel a pending friend request",
      description =
          "Deletes a PENDING request. The requester cancels their own outgoing request; the "
              + "addressee rejects an incoming one — both delete the row (there is no REJECTED "
              + "state, so re-requesting later is allowed). A non-participant or unknown id gets "
              + "404 FRIEND_REQUEST_NOT_FOUND (no existence leak). Requires a valid Bearer JWT.")
  @ApiResponse(responseCode = "204", description = "Request deleted.")
  @ApiResponse(
      responseCode = "401",
      description = "Missing or invalid JWT.",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description =
          "FRIEND_REQUEST_NOT_FOUND — the request does not exist or the caller is not a participant.",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @DeleteMapping("/friends/requests/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteRequest(@AuthenticationPrincipal User caller, @PathVariable UUID id) {
    friendshipService.deleteRequest(id, caller.getId());
  }

  @Operation(
      summary = "Remove an accepted friend",
      description =
          "Removes an ACCEPTED friendship with the user named by the path id. Either party may "
              + "remove. Requires a valid Bearer JWT.")
  @ApiResponse(responseCode = "204", description = "Friendship removed.")
  @ApiResponse(
      responseCode = "401",
      description = "Missing or invalid JWT.",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description = "FRIEND_NOT_FOUND — no accepted friendship exists with that user.",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @DeleteMapping("/friends/{userId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void removeFriend(@AuthenticationPrincipal User caller, @PathVariable UUID userId) {
    friendshipService.removeFriend(caller.getId(), userId);
  }

  @Operation(
      summary = "List the authenticated user's friends",
      description =
          "Paginated ACCEPTED friends, newest first, projecting each friend's live display name and "
              + "friend code. Pagination: page (default 0, min 0); size (default 20, min 1, max "
              + "100). Requires a valid Bearer JWT.")
  @ApiResponse(
      responseCode = "200",
      description = "Page of friends (possibly empty).",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = FriendsPage.class)))
  @ApiResponse(
      responseCode = "400",
      description = "Invalid pagination parameter (page < 0 or size outside [1, 100]).",
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
  @GetMapping("/friends")
  public Page<FriendResponse> listFriends(
      @AuthenticationPrincipal User caller,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
    return friendshipService
        .listFriends(caller.getId(), PageRequest.of(page, size))
        .map(FriendshipController::toFriendResponse);
  }

  @Operation(
      summary = "List incoming pending friend requests",
      description =
          "Paginated PENDING requests where the caller is the addressee, newest first. Same "
              + "pagination contract as the friends list. Requires a valid Bearer JWT.")
  @ApiResponse(
      responseCode = "200",
      description = "Page of incoming requests (possibly empty).",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = FriendRequestsPage.class)))
  @ApiResponse(
      responseCode = "400",
      description = "Invalid pagination parameter (page < 0 or size outside [1, 100]).",
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
  @GetMapping("/friends/requests/incoming")
  public Page<FriendRequestResponse> listIncomingRequests(
      @AuthenticationPrincipal User caller,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
    return friendshipService
        .listIncomingRequests(caller.getId(), PageRequest.of(page, size))
        .map(FriendshipController::toRequestResponse);
  }

  @Operation(
      summary = "List outgoing pending friend requests",
      description =
          "Paginated PENDING requests where the caller is the requester, newest first. Same "
              + "pagination contract as the friends list. Requires a valid Bearer JWT.")
  @ApiResponse(
      responseCode = "200",
      description = "Page of outgoing requests (possibly empty).",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = FriendRequestsPage.class)))
  @ApiResponse(
      responseCode = "400",
      description = "Invalid pagination parameter (page < 0 or size outside [1, 100]).",
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
  @GetMapping("/friends/requests/outgoing")
  public Page<FriendRequestResponse> listOutgoingRequests(
      @AuthenticationPrincipal User caller,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
    return friendshipService
        .listOutgoingRequests(caller.getId(), PageRequest.of(page, size))
        .map(FriendshipController::toRequestResponse);
  }

  private static FriendResponse toFriendResponse(FriendSummary s) {
    return new FriendResponse(s.userId(), s.displayName(), s.friendCode(), s.friendsSince());
  }

  private static FriendRequestResponse toRequestResponse(FriendRequestSummary s) {
    return new FriendRequestResponse(
        s.requestId(), s.userId(), s.displayName(), s.friendCode(), s.createdAt());
  }

  /**
   * Schema-only carrier documenting the {@code Page<FriendResponse>} shape in the OpenAPI spec.
   * Mirrors {@code MyGamesController.MyGamesPage}: the controller returns the Spring-native {@code
   * Page} so Jackson emits the standard envelope; this record exists only so springdoc renders a
   * strongly-typed Page in Swagger UI. Never instantiated at runtime.
   */
  @Schema(
      name = "FriendsPage",
      description = "Spring Data Page envelope for FriendResponse entries.")
  public record FriendsPage(
      @Schema(description = "Friends on this page, newest first.") List<FriendResponse> content,
      @Schema(description = "Total number of friends across all pages.", example = "12")
          long totalElements,
      @Schema(description = "Total number of pages.", example = "1") int totalPages,
      @Schema(description = "Page size requested (or the default).", example = "20") int size,
      @Schema(description = "Zero-based page index.", example = "0") int number,
      @Schema(description = "True when this is the first page.", example = "true") boolean first,
      @Schema(description = "True when this is the last page.", example = "true") boolean last,
      @Schema(description = "Number of entries on this page.", example = "12") int numberOfElements,
      @Schema(description = "True when this page is empty.", example = "false") boolean empty) {}

  /**
   * Schema-only carrier documenting the {@code Page<FriendRequestResponse>} shape in the OpenAPI
   * spec (incoming and outgoing share it). Same rationale as {@link FriendsPage}.
   */
  @Schema(
      name = "FriendRequestsPage",
      description = "Spring Data Page envelope for FriendRequestResponse entries.")
  public record FriendRequestsPage(
      @Schema(description = "Requests on this page, newest first.")
          List<FriendRequestResponse> content,
      @Schema(description = "Total number of requests across all pages.", example = "3")
          long totalElements,
      @Schema(description = "Total number of pages.", example = "1") int totalPages,
      @Schema(description = "Page size requested (or the default).", example = "20") int size,
      @Schema(description = "Zero-based page index.", example = "0") int number,
      @Schema(description = "True when this is the first page.", example = "true") boolean first,
      @Schema(description = "True when this is the last page.", example = "true") boolean last,
      @Schema(description = "Number of entries on this page.", example = "3") int numberOfElements,
      @Schema(description = "True when this page is empty.", example = "false") boolean empty) {}
}
