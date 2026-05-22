-- Initial schema for the postgres-game-history feature.
--
-- Two tables, normalised on the relational side but denormalised w.r.t. players:
--   * games   — one row per archived (terminal-status) game.
--   * moves   — N rows per game, ordered by move_idx, FK to games(id) ON DELETE CASCADE.
--
-- Column types are picked deliberately:
--   * id / *_player_id columns are UUID — native Postgres uuid. The Java domain (Player.id,
--     Game.id) carries java.util.UUID end-to-end and Hibernate maps it to the native uuid column
--     out of the box (no @JdbcTypeCode trick). On the JSON wire Jackson serialises UUID to a
--     plain string, so the REST contract is identical to the previous TEXT-backed version.
--   * room_id is VARCHAR(6) — the room code is a 6-char string from the custom alphabet
--     "ABCDEFGHJKMNPQRSTUVWXYZ23456789" (feature 4). It is not a UUID.
--   * *_display_name is VARCHAR(100). The web DTOs only require @NotBlank, so 100 is a
--     generous bound for the audit-time snapshot we capture here.
--   * starting_fen / final_fen are VARCHAR(100) — a maximal FEN string is around 90 chars.
--   * status is VARCHAR(20) — longest current value is "STALEMATE" (9 chars), with margin
--     for future GameStatus additions. No CHECK constraint: JPA's @Enumerated(STRING)
--     already enforces the alphabet on the only writer.
--   * from_square / to_square are VARCHAR(2) — the domain stores squares as the canonical
--     lowercase two-character form (e.g. "e2"). CHAR(2) was the first choice, but Hibernate's
--     ddl-auto: validate compares the column's java.sql.Types code: Postgres reports CHAR
--     as Types.CHAR while Hibernate maps a Java String column to Types.VARCHAR, so the
--     types-codes mismatch blocks boot even though both columns store the same bytes.
--     VARCHAR(2) carries the exact same length cap with no functional difference.
--   * promotion is VARCHAR(10) nullable — longest current Piece.name() is "KNIGHT" (6).
--
-- Player information is denormalised onto `games` (white_player_id, white_display_name,
-- black_player_id, black_display_name) on purpose. Two reasons:
--   1. Snapshot semantics — the display name at game time is the audit-correct value, the
--      same way Lichess, GitHub commits, and Steam friend graphs handle author/handle
--      history. Renames after the fact must not rewrite past games.
--   2. No real Player identity today — guests with ephemeral UUIDs, no email, no auth.
--      A `players` table would only duplicate the UUID + display name with no extra
--      attached data and force a join on every history query.
-- When auth lands, the migration path is V2__create_players.sql extracting distinct UUIDs
-- from `games` with kind='HISTORICAL_GUEST' and adding a FK — well-defined and obvious.
--
-- No Postgres ENUM types for `status` or `promotion`: @Enumerated(STRING) enforces enum
-- validity from the only client writing this DB; Postgres ENUM adds migration churn on
-- every enum value addition/rename; Hibernate→Postgres ENUM requires extra boilerplate
-- (@JdbcTypeCode(SqlTypes.NAMED_ENUM) or AttributeConverter). The idiomatic Spring/JPA
-- pattern — used by GitHub, Lichess, the canonical Spring guides — is VARCHAR + STRING.

CREATE TABLE games (
    id                  UUID         PRIMARY KEY,
    room_id             VARCHAR(6)   NOT NULL,
    white_player_id     UUID         NOT NULL,
    white_display_name  VARCHAR(100) NOT NULL,
    black_player_id     UUID         NOT NULL,
    black_display_name  VARCHAR(100) NOT NULL,
    starting_fen        VARCHAR(100) NOT NULL,
    final_fen           VARCHAR(100) NOT NULL,
    status              VARCHAR(20)  NOT NULL,
    ended_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE moves (
    game_id     UUID        NOT NULL REFERENCES games(id) ON DELETE CASCADE,
    move_idx    INTEGER     NOT NULL,
    from_square VARCHAR(2)  NOT NULL,
    to_square   VARCHAR(2)  NOT NULL,
    promotion   VARCHAR(10),
    PRIMARY KEY (game_id, move_idx)
);

CREATE INDEX idx_games_white_player ON games (white_player_id, ended_at DESC);
CREATE INDEX idx_games_black_player ON games (black_player_id, ended_at DESC);
