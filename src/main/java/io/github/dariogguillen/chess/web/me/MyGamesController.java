package io.github.dariogguillen.chess.web.me;

import io.github.dariogguillen.chess.domain.Side;
import io.github.dariogguillen.chess.domain.User;
import io.github.dariogguillen.chess.exception.ErrorResponse;
import io.github.dariogguillen.chess.persistence.ArchivedGamePlayerView;
import io.github.dariogguillen.chess.service.GameHistoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoint for the authenticated user's archived game history.
 *
 * <p>Activated by feature 19 (`auth-my-games`): {@code GET /api/me/games?page=&size=} returns a
 * page of {@link MyGameSummary} records, newest first, filtered by {@code white_user_id} or {@code
 * black_user_id} matching the authenticated user. Requires a valid Bearer JWT — Spring Security's
 * filter chain 401s every unauthenticated call before this controller method runs, so the
 * {@code @AuthenticationPrincipal User currentUser} parameter is non-null inside the method body by
 * construction.
 *
 * <p>The guest-friendly {@code GET /api/players/{id}/games} endpoint stays open and unchanged for
 * anonymous play — the two endpoints serve two distinct audiences and live side by side.
 *
 * <p>{@link Validated} on the class enables JSR-380 constraints on individual method parameters
 * ({@code @Min}, {@code @Max}). Without it, the parameter-level annotations would be silently
 * ignored — Spring's {@code @Valid} on a {@code @RequestBody} works through a different machinery
 * (method-argument validation post-processor) and doesn't cover {@code @RequestParam}.
 */
@Tag(name = "Authentication", description = "Authenticated-user endpoints.")
@RestController
@RequestMapping("/api/me/games")
@Validated
public class MyGamesController {

  /** Hard cap on the page size; mirrors typical "my-games" UIs and keeps the result-set bounded. */
  static final int MAX_PAGE_SIZE = 100;

  private final GameHistoryService gameHistoryService;

  public MyGamesController(GameHistoryService gameHistoryService) {
    this.gameHistoryService = gameHistoryService;
  }

  @Operation(
      summary = "List the authenticated user's archived games",
      description =
          "Returns the archived (terminal-status) games where the caller participated as either "
              + "side, newest first, in the standard Spring Data Page envelope. Pagination params: "
              + "page (default 0, min 0); size (default 20, min 1, max 100). Requires a valid "
              + "Bearer JWT — anonymous requests get 401 AUTHENTICATION_REQUIRED. The page shape "
              + "is the standard Spring Data envelope: { content, totalElements, totalPages, "
              + "size, number, ... }.")
  @ApiResponse(
      responseCode = "200",
      description = "Page of archived games (possibly empty).",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = MyGamesPage.class)))
  @ApiResponse(
      responseCode = "400",
      description = "Invalid pagination parameter (page < 0 or size outside [1, 100]).",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description =
          "Missing, malformed, expired, or unsigned-by-us JWT. Body is the standard ErrorResponse "
              + "envelope with code AUTHENTICATION_REQUIRED.",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @GetMapping
  public Page<MyGameSummary> getMyGames(
      @AuthenticationPrincipal User currentUser,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
    UUID userId = currentUser.getId();
    Page<ArchivedGamePlayerView> archived =
        gameHistoryService.findByUser(userId, PageRequest.of(page, size));
    return archived.map(view -> toSummary(view, userId));
  }

  /**
   * Maps an {@link ArchivedGamePlayerView} to a {@link MyGameSummary}, deriving {@code selfSide}
   * and {@code opponentDisplayName} from which user-id side of the view matches {@code userId}. The
   * repository query restricts results to games where the user was on exactly one side, so the
   * {@code white} branch is checked first and the {@code black} branch covers the remainder.
   */
  private static MyGameSummary toSummary(ArchivedGamePlayerView view, UUID userId) {
    boolean userIsWhite = userId.equals(view.whiteUserId());
    Side selfSide = userIsWhite ? Side.WHITE : Side.BLACK;
    String opponentDisplayName = userIsWhite ? view.blackDisplayName() : view.whiteDisplayName();
    return new MyGameSummary(
        view.id(),
        view.roomId(),
        opponentDisplayName,
        selfSide,
        view.status(),
        view.endedAt(),
        view.moveCount());
  }

  /**
   * Schema-only carrier documenting the Spring Data {@code Page<MyGameSummary>} shape in the
   * OpenAPI spec. The controller returns the Spring-native {@code Page} so Jackson emits the
   * standard envelope ({@code content}, {@code totalElements}, {@code totalPages}, {@code size},
   * {@code number}, {@code first}, {@code last}, {@code numberOfElements}, {@code empty}) without a
   * wrapper class. This record is referenced from the {@code @ApiResponse(200)} schema attribute so
   * springdoc renders a strongly-typed Page in Swagger UI — without it, the spec would show {@code
   * Page<MyGameSummary>} as a generic Page with {@code content: object[]}, losing the payload shape
   * information.
   *
   * <p>The record is never instantiated at runtime: Jackson serialises the actual {@code
   * PageImpl<MyGameSummary>} returned by the controller, not this record.
   */
  @Schema(
      name = "MyGamesPage",
      description =
          "Spring Data Page envelope for MyGameSummary entries. Carries the page contents plus "
              + "the pagination metadata the frontend uses to render navigation controls.")
  public record MyGamesPage(
      @Schema(description = "Games on this page, newest first.") List<MyGameSummary> content,
      @Schema(description = "Total number of games across all pages.", example = "42")
          long totalElements,
      @Schema(description = "Total number of pages.", example = "3") int totalPages,
      @Schema(description = "Page size that was requested (or the default).", example = "20")
          int size,
      @Schema(description = "Zero-based page index.", example = "0") int number,
      @Schema(description = "True when this is the first page.", example = "true") boolean first,
      @Schema(description = "True when this is the last page.", example = "false") boolean last,
      @Schema(description = "Number of entries on this page.", example = "20") int numberOfElements,
      @Schema(description = "True when this page is empty.", example = "false") boolean empty) {}
}
