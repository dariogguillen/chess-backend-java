package io.github.dariogguillen.chess.web.me;

import io.github.dariogguillen.chess.persistence.UserGameStatsView;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Wire-format aggregate win/loss/draw record for the {@code GET /api/me/stats} response (feature
 * 23.93, {@code me-stats}). Built from the single-row {@link UserGameStatsView} the JPQL aggregate
 * returns, plus the {@link #winRate} computed in {@link io.github.dariogguillen.chess.service
 * .GameHistoryService} with a divide-by-zero guard.
 *
 * <p>The buckets reconcile: {@code total == wins + losses + draws + unknown}. The {@code unknown}
 * bucket holds legacy {@code NULL}-result rows (old ABANDONED games whose winner is unrecoverable);
 * those count toward {@code total} but are excluded from W/L/D and from the {@code winRate}
 * denominator. Bot games are included — the human side carries the user id, so the {@code
 * white_user_id = :uid OR black_user_id = :uid} filter naturally includes them; a vs-human-only
 * split is a later follow-up.
 *
 * @param total all archived games where the user is white_user_id OR black_user_id.
 * @param wins games the user won.
 * @param losses games the user lost.
 * @param draws games drawn.
 * @param unknown legacy NULL-result games (counted in total, excluded from W/L/D and winRate).
 * @param winRate wins over decided games; 0.0 when there are no decided games.
 */
@Schema(description = "Aggregate win/loss/draw record for the authenticated user.")
public record MyStatsResponse(
    @Schema(
            description =
                "All archived games where the user is white_user_id OR black_user_id. "
                    + "Equals wins + losses + draws + unknown.",
            example = "5")
        long total,
    @Schema(description = "Games the user won.", example = "2") long wins,
    @Schema(description = "Games the user lost.", example = "1") long losses,
    @Schema(description = "Games drawn.", example = "1") long draws,
    @Schema(
            description =
                "Legacy NULL-result games (old ABANDONED rows whose winner is unrecoverable). "
                    + "Counted in total but excluded from W/L/D and from the winRate denominator.",
            example = "1")
        long unknown,
    @Schema(
            description =
                "Win rate over decided games (unknown-result games excluded from the denominator); "
                    + "0.0 when there are no decided games.",
            example = "0.5")
        double winRate) {

  /**
   * Builds the response from the persistence aggregate, computing the {@code winRate} over decided
   * games ({@code wins + losses + draws}) with a divide-by-zero guard: when no games are decided
   * the rate is {@code 0.0}. The {@code unknown} bucket is excluded from the denominator by
   * construction.
   *
   * @param view the single-row aggregate from the JPQL query; non-null.
   * @return the wire-format response with the win rate filled in.
   */
  public static MyStatsResponse from(UserGameStatsView view) {
    long decided = view.wins() + view.losses() + view.draws();
    double winRate = decided == 0L ? 0.0 : (double) view.wins() / decided;
    return new MyStatsResponse(
        view.total(), view.wins(), view.losses(), view.draws(), view.unknown(), winRate);
  }
}
