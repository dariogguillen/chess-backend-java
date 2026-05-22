# Current session

**Status:** closed — no active feature.

Last closed feature: `postgres-game-history` (priority 9) on 2026-05-22.
See `progress/history.md` for the full entry.

---

To start the next session, the leader picks the lowest-priority
feature with `status: "pending"` from `feature_list.json` and writes
a plan here.

Next in queue: `rest-cors` (priority 10).

---

## Game history is now persisted to Postgres

Completed games (`CHECKMATE`, `STALEMATE`, `DRAW`, `ABANDONED`) are
archived to Postgres synchronously inside `GameService.applyMove`,
before the Redis active-state write. Postgres is the archive layer;
Redis (feature 8) remains the source of truth for ongoing games.

`GET /api/players/{id}/games` returns the player's history (newest
first, hard cap 50). Unknown player → 200 with `[]` (guests have no
registry).

The round-2 cleanup made the type model honest: `UUID` end-to-end
(DB, entity, domain) for ids that are actually UUIDs; `String` for
the short-code `Room.id` / `Game.roomId`; bounded `VARCHAR(N)` with
`@Column(length = N)` + `ddl-auto: validate` enforcing the lengths
at boot. JSON wire format unchanged — Jackson serialises `UUID` to
string identically. No frontend coordination needed for this
feature.

Production EC2 already runs RDS Postgres (since feature 7.5), so
the next push to `main` will apply `V1__create_game_history.sql`
automatically; no infra changes required.

Live URLs (unchanged):

- `https://chess-backend.duckdns.org/api/health`
- `https://chess-backend.duckdns.org/swagger-ui/index.html`
- `https://chess-backend.duckdns.org/api/players/{id}/games` (new)
