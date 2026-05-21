# Current session

**Status:** closed — no active feature.

Last closed feature: `redis-active-state` (priority 8) on 2026-05-21.
See `progress/history.md` for the full entry.

---

To start the next session, the leader picks the lowest-priority
feature with `status: "pending"` from `feature_list.json` and writes
a plan here.

Next in queue: `postgres-game-history` (priority 9).

---

## Active state now lives in Redis

The in-memory `Room` and `Game` stores are gone. The two `RedisTemplate<String, T>`
beans in `config/RedisConfig.java` back the same `RoomStore` / `GameStore`
interfaces — every existing IT passed unchanged against the new
implementations, confirming the seam worked end-to-end.

Production EC2 already runs `redis:7-alpine` alongside the app (since
feature 7.5), so the next push to `main` will pick up the new stores
automatically; no infra changes required.

Live URLs (unchanged):

- `https://chess-backend.duckdns.org/api/health`
- `https://chess-backend.duckdns.org/swagger-ui/index.html`
