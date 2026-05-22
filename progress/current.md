# Current session

**Status:** closed — no active feature.

Last closed feature: `rest-cors` (priority 10) on 2026-05-22.
See `progress/history.md` for the full entry.

---

To start the next session, the leader picks the lowest-priority
feature with `status: "pending"` from `feature_list.json` and writes
a plan here.

Next in queue: `disconnect-handling` (priority 11).

---

## Production E2E unblocked for the frontend

The browser no longer blocks cross-origin REST calls to `/api/**`.
The deployed frontend on GitHub Pages can now talk to the backend
on `https://chess-backend.duckdns.org` directly.

CORS lives entirely in the Spring layer (`CorsConfig`), Caddy passes
the headers through unchanged. The allowed-origin-patterns list is
in `chess.cors.allowed-origin-patterns` — one source of truth that
both REST (`/api/**`) and STOMP (`/ws`) read from. The env-var
override (`CHESS_CORS_ALLOWED_ORIGIN_PATTERNS`) lets production add
a preview origin without recompiling.

`allowCredentials: false` for now (stateless JSON identity). When
auth lands, the posture flips as part of that feature.

Frontend cross-repo punch list status:

- **#1 CORS** — done (this feature).
- **#2 `GET /api/rooms/{id}`** — done (feature 9.5).
- **#3 `/topic/rooms/{id}` STOMP** — done (feature 9.5).
- **#4 frontend prep (Vite proxy + `creator-game-discovery`)** —
  frontend's own scope, not blocked by backend.

The whole punch list is closed on the backend side. Next backend
features (`disconnect-handling`, etc.) are not gating frontend work.

Live URLs (unchanged):

- `https://chess-backend.duckdns.org/api/health`
- `https://chess-backend.duckdns.org/swagger-ui/index.html`
- `https://chess-backend.duckdns.org/api/rooms/{id}` (added in 9.5)

CORS allowed origins on `/api/**` and `/ws`:

- `https://dariogguillen.github.io`
- `http://localhost:*`
