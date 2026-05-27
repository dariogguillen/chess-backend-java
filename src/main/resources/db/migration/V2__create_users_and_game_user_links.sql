-- Schema additions for the auth-core feature (feature 16).
--
-- Two things land here in a single migration:
--
--   1. `users` — the canonical account record. One row per registered/authenticated user.
--   2. Two nullable FK columns on the existing `games` table — white_user_id and
--      black_user_id — each REFERENCES users(id). They are the User-to-Game link.
--
-- WHY no `players` table.
--
-- V1__create_game_history.sql is deliberately denormalised on the player side:
-- (white_player_id, white_display_name) and (black_player_id, black_display_name) live
-- directly on `games`. Two reasons documented at length in V1's top-of-file comment:
-- snapshot semantics (the display name at game-time is the audit-correct value, the same
-- way Lichess and GitHub commits handle author handle history — a future rename must not
-- rewrite past games), and the absence of any extra attached data on players (guests have
-- nothing beyond a UUID and a display name, so an intermediate `players` row would buy
-- nothing and force a join on every history query).
--
-- A first implementation pass of this migration created an intermediate `players` table
-- (id, display_name, user_id FK, kind, created_at) intending to bridge games.player_id to
-- users.id. That design was rejected by the user during review for the same reasons V1
-- avoided one: an intermediate table that duplicates the (UUID + display_name) snapshot
-- and adds a join contradicts V1's deliberate denormalisation. The cleanest auth
-- extension is to add the user FK directly alongside the existing player snapshot — one
-- nullable UUID column per side, with a partial index for the populated-only path so
-- guest games do not bloat it.
--
-- WHY the existing games.{white,black}_player_id columns are NOT migrated.
--
-- Those columns are the audit-time identity snapshot — the ephemeral UUID a guest was
-- assigned at game creation. They stay unconstrained UUIDs with NO FK to users(id):
--   * A guest may never sign up, so most player_id values will never have a matching user.
--   * Even when an authenticated user plays a game, their games.*_player_id is the per-
--     session player identity (RoomService mints it), independent of users.id by design.
-- Feature 19 (`auth-my-games`) starts populating the new white_user_id / black_user_id
-- columns from the authenticated request context; the historical player_id columns are
-- preserved unchanged for backward-compat with /api/players/{id}/games and the snapshot
-- audit story V1 documents.
--
-- Column-type conventions match V1: native UUID (no @JdbcTypeCode trick needed),
-- VARCHAR with explicit caps that Hibernate `ddl-auto: validate` will check against the
-- JPA entity, TIMESTAMPTZ with `DEFAULT now()` for audit-only timestamps. Caps chosen
-- against well-known standards so the model survives real input:
--   * email = VARCHAR(254). RFC 5321 path maximum.
--   * password_hash = VARCHAR(60). BCrypt produces a fixed 60-char string by spec.
--   * google_sub = VARCHAR(255). Google's documented `sub` claim upper bound.
--   * display_name = VARCHAR(100). Same cap V1 uses for games.{white,black}_display_name,
--     keeping the two display-name surfaces aligned.
--
-- A partial unique index enforces "at most one user per Google subject" while allowing
-- multiple NULL google_sub rows (the email-only users). Postgres treats NULLs as distinct
-- in a plain UNIQUE constraint already, but the partial-index form documents the intent
-- and avoids any index maintenance for NULL rows.
--
-- ddl-auto: validate compatibility. Hibernate's `validate` mode only checks columns
-- declared by JPA entities; extra tables/columns are tolerated. This feature ships only
-- the User entity, so `users` is validated; the two new columns on `games` are not
-- mapped by GameEntity and stay dormant until feature 19 introduces them on the entity.

CREATE TABLE users (
    id            UUID         PRIMARY KEY,
    email         VARCHAR(254) NOT NULL UNIQUE,
    display_name  VARCHAR(100) NOT NULL,
    password_hash VARCHAR(60),
    google_sub    VARCHAR(255),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Partial unique index: enforces "at most one user per Google subject" but allows
-- multiple NULLs (the email-only users). The partial-index form documents intent and
-- skips index maintenance for NULL rows.
CREATE UNIQUE INDEX idx_users_google_sub
    ON users(google_sub)
    WHERE google_sub IS NOT NULL;

-- The User-to-Game link. Two nullable FK columns on `games`, one per side. Anonymous
-- games leave both NULL forever (fresh-start identity model, confirmed with the user
-- 2026-05-27). Authenticated game creation (feature 19) populates the side matching the
-- authenticated user; the existing games.{white,black}_player_id snapshot is untouched.
ALTER TABLE games
    ADD COLUMN white_user_id UUID NULL REFERENCES users(id),
    ADD COLUMN black_user_id UUID NULL REFERENCES users(id);

-- Partial indexes scoped to populated rows only. Most games will be guest games with
-- both columns NULL, so an unconditional index would store entries for rows the
-- "my games" query (feature 19) does not care about. The partial WHERE clause is the
-- standard pattern for a "sparse FK" — narrow index, fast lookup, no bloat.
CREATE INDEX idx_games_white_user_id
    ON games(white_user_id)
    WHERE white_user_id IS NOT NULL;

CREATE INDEX idx_games_black_user_id
    ON games(black_user_id)
    WHERE black_user_id IS NOT NULL;
