package io.github.dariogguillen.chess.web.me;

import io.github.dariogguillen.chess.domain.User;
import io.github.dariogguillen.chess.exception.ErrorResponse;
import io.github.dariogguillen.chess.service.GameHistoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoint for the authenticated user's aggregate win/loss/draw record.
 *
 * <p>Added by feature 23.93 (`me-stats`): {@code GET /api/me/stats} returns a {@link
 * MyStatsResponse} with total / wins / losses / draws / unknown counts plus a win rate over decided
 * games. Requires a valid Bearer JWT — Spring Security's {@code /api/me/**} filter chain 401s every
 * unauthenticated call before this method runs, so {@code @AuthenticationPrincipal User user} is
 * non-null inside the body by construction.
 *
 * <p>The win/loss derivation cross-references the persisted {@code games.result} column (feature
 * 23.92) with the user's side ({@code white_user_id} vs {@code black_user_id}); the whole aggregate
 * is one JPQL conditional-sum query that never loads the game rows. See {@link
 * io.github.dariogguillen.chess.persistence.GameHistoryRepository#statsForUser(java.util.UUID)}.
 */
@Tag(name = "Authentication", description = "Authenticated-user endpoints.")
@RestController
@RequestMapping("/api/me/stats")
public class MeStatsController {

  private final GameHistoryService gameHistoryService;

  public MeStatsController(GameHistoryService gameHistoryService) {
    this.gameHistoryService = gameHistoryService;
  }

  @Operation(
      summary = "Get the authenticated user's win/loss/draw record",
      description =
          "Returns the caller's aggregate record across all archived games where they participated "
              + "as either side: total, wins, losses, draws, an 'unknown' bucket for legacy "
              + "NULL-result games (old ABANDONED rows whose winner is unrecoverable), and a "
              + "winRate over decided games (unknown-result games excluded from the denominator). "
              + "The buckets reconcile: total == wins + losses + draws + unknown. Bot games are "
              + "included (the human side carries the user id); a vs-human-only split is a later "
              + "follow-up. Requires a valid Bearer JWT — anonymous requests get 401 "
              + "AUTHENTICATION_REQUIRED.")
  @ApiResponse(
      responseCode = "200",
      description = "The authenticated user's aggregate win/loss/draw record.",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = MyStatsResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Missing, malformed, expired, or unsigned-by-us JWT.",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @GetMapping
  public MyStatsResponse stats(@AuthenticationPrincipal User user) {
    return gameHistoryService.statsFor(user.getId());
  }
}
