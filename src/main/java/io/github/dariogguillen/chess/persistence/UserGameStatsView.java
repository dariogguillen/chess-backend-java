package io.github.dariogguillen.chess.persistence;

import java.util.UUID;

/**
 * JPQL constructor-projection view of a user's aggregate win/loss/draw record, used by {@link
 * GameHistoryRepository#statsForUser(UUID)} to back the {@code GET /api/me/stats} endpoint (feature
 * 23.93, {@code me-stats}).
 *
 * <p>The single-row aggregate is computed entirely in SQL: a {@code COUNT(g)} for {@link #total}
 * plus four {@code COALESCE(SUM(CASE WHEN ... THEN 1L ELSE 0L END), 0L)} conditional sums for the
 * decided buckets. The {@code COALESCE} matters — {@code SUM} over zero matching rows returns
 * {@code NULL} in SQL, and a user with zero games would otherwise bind {@code null} into the {@code
 * long} components and throw at extraction time. Wrapping each sum in {@code COALESCE(..., 0L)}
 * guarantees the zero-games case yields {@code total=0} with all-zero buckets.
 *
 * <p>All components are {@code long}: Hibernate binds {@code COUNT} / {@code SUM} as {@code Long},
 * but the {@code COALESCE(..., 0L)} guard removes the only source of {@code null}, so the primitive
 * binding is safe. The {@code winRate} is deliberately <em>not</em> computed here — the division is
 * a Java concern (guard against divide-by-zero in {@link io.github.dariogguillen.chess.service
 * .GameHistoryService}), not a JPQL one.
 *
 * <p>The invariant {@code total == wins + losses + draws + unknown} holds by construction: every
 * row matched by the {@code WHERE} clause lands in exactly one of the four mutually-exclusive
 * buckets (the {@code result} column is either {@code WHITE_WIN}, {@code BLACK_WIN}, {@code DRAW},
 * or {@code NULL}, and the side cross-reference partitions the two decisive results into win/loss).
 *
 * <p><strong>Component order is load-bearing:</strong> it must match the {@code SELECT new
 * UserGameStatsView(...)} clause exactly, or Hibernate binds the wrong column to the wrong field
 * with no compile-time error.
 *
 * @param total all archived games where the user is white_user_id OR black_user_id.
 * @param wins games the user won (its result names the user's side).
 * @param losses games the user lost (its result names the opponent's side).
 * @param draws games drawn ({@code result = DRAW}).
 * @param unknown legacy {@code NULL}-result rows (old ABANDONED games whose winner is
 *     unrecoverable); counted in {@link #total} but excluded from the win-rate denominator.
 */
public record UserGameStatsView(long total, long wins, long losses, long draws, long unknown) {}
