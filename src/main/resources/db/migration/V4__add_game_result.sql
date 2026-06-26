-- Add the game RESULT (who won) to the archive, for the game-result-persistence feature
-- (feature 23.92) — the foundation for per-user W/L/D statistics.
--
-- V1 stored only `status` (CHECKMATE / STALEMATE / DRAW / ABANDONED / TIMEOUT) — HOW the game
-- ended, never WHO won. The winner was computed at runtime in the four terminal paths and broadcast
-- on the STOMP events, then dropped at archive time. This migration adds the column the domain now
-- carries (Game.result, GameEntity.result) and backfills the rows we can derive.
--
-- Column type matches the `status` house style exactly: VARCHAR(20), nullable, NO CHECK constraint.
-- The only writer is JPA with @Enumerated(EnumType.STRING) on a GameResult enum
-- { WHITE_WIN, BLACK_WIN, DRAW }, which already enforces the alphabet — same reasoning V1 documents
-- for `status` (a Postgres ENUM or CHECK would add migration churn on every enum change for no extra
-- safety). 20 chars is generous: the longest current value is "BLACK_WIN" (9).
--
-- Nullable on purpose: a non-terminal game has no result, a draw maps to 'DRAW', and a legacy
-- ABANDONED row whose winner is unrecoverable stays NULL (see the backfill note below).

ALTER TABLE games ADD COLUMN result VARCHAR(20) NULL;

-- One-time backfill of existing archived rows, derived from `status` + `final_fen`.
--
-- The active color in a FEN is its second space-separated field ('w' = white to move, 'b' = black
-- to move). For a CHECKMATE or a TIMEOUT, the side TO MOVE at the final position is the side that
-- was mated (no legal response to check) or that flagged (ran out of time) — i.e. the LOSER. So:
--   * white to move ('w')  => white lost => BLACK_WIN
--   * black to move ('b')  => black lost => WHITE_WIN
-- The enum-name string literals below match GameResult exactly (WHITE_WIN / BLACK_WIN / DRAW); the
-- @Enumerated(STRING) reader will reject any drift on the next boot's ddl-auto: validate.
--
-- STALEMATE and DRAW are both draws -> 'DRAW'.
--
-- ABANDONED rows are deliberately LEFT NULL. The abandoner (the loser) is the disconnected player,
-- whose identity is NOT encoded anywhere in the final FEN — it is a side-channel fact known only at
-- runtime when the grace timer fired, and that fact was never persisted before this feature. There
-- is no way to recover the winner from an old ABANDONED row, so NULL ("unknown") is the honest
-- value. New ABANDONED games archived after this feature DO carry the result (the terminal path now
-- stamps it), so this gap is bounded to pre-feature history. Likewise any non-terminal or unexpected
-- status stays NULL via the absence of a matching WHEN branch.
UPDATE games
SET result = CASE
    WHEN status IN ('STALEMATE', 'DRAW') THEN 'DRAW'
    WHEN status IN ('CHECKMATE', 'TIMEOUT') AND split_part(final_fen, ' ', 2) = 'w' THEN 'BLACK_WIN'
    WHEN status IN ('CHECKMATE', 'TIMEOUT') AND split_part(final_fen, ' ', 2) = 'b' THEN 'WHITE_WIN'
    ELSE NULL
END
WHERE result IS NULL;
