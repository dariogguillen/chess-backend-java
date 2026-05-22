# Current session

**Status:** closed — no active feature.

Last closed feature: `broadcast-observability` (priority 11.8) on 2026-05-22.
See `progress/history.md` for the full entry.

---

To start the next session, the leader picks the lowest-priority
feature with `status: "pending"` from `feature_list.json` and writes
a plan here.

Next in queue: `docker-compose` (priority 12) — likely redundant
with feature 7 (`backend-containerize`), audit first.

---

## Cross-repo work items — all resolved

All three frontend-reported flags are closed (re-verified against the
frontend repo's `progress/current.md`, which now declares
"Backend shipped all three fixes ... the full two-browser E2E flow
now works against the live backend"):

- ✅ #1 X-Player-Id CORS — closed via 11.7.
- ✅ #2 `RoomService.findById` 404 on ACTIVE rooms — investigation
  closed. The frontend's report described a service-layer filter
  that does NOT exist in the actual code (`RoomService.findById`,
  `RedisRoomStore.findById`, and `RoomController.getRoom` are all
  status-agnostic passthroughs). Most likely the original 404 was
  endpoint-missing (the frontend smoke ran before feature 9.5's
  `GET /api/rooms/{id}` was running). The frontend additionally
  shipped a round-2 client-side soft-fix that tolerates GET 404
  gracefully, so even in a hypothetical recurrence the STOMP path
  delivers the gameId.
- ✅ #3 `RoomJoinedEvent` broadcast — confirmed transient client-
  side via the TRACE log diagnosis. Observability layer added in
  11.8 so future similar issues are visible at INFO.

---

## Production readiness checklist

Eight features ship to production on the next push to `main`:
8, 9, 9.5, 10, 11, 11.5, 11.7, 11.8. None requires infra changes
(all env vars have defaults that match the previous production
configuration). The Flyway migration `V1__create_game_history.sql`
runs automatically on app boot against RDS.

**Before the push, verify in this order:**

1. **Split commits** — feature 11.7 (cors-x-player-id) and 11.8
   (broadcast-observability) are staged together in the working
   tree. Commit them separately so each git log entry is one
   logical feature.
2. **Revert the local `docker-compose.yml` modification** — the
   `app:` service block is commented out for local
   `mvnw spring-boot:run` testing. That comment must NOT ship to
   production. `git checkout -- docker-compose.yml` before staging.
3. **Confirm `docker-compose.prod.yml`** has no unexpected local
   modifications.

After the push, the GitHub Actions `deploy.yml` workflow handles:

- Build + run `./init.sh` + push image to ECR.
- SSH to EC2, `docker compose pull && up -d`.
- Smoke test `https://chess-backend.duckdns.org/api/health`.

Live URLs (unchanged):

- `https://chess-backend.duckdns.org/api/health`
- `https://chess-backend.duckdns.org/swagger-ui/index.html`
- `https://dariogguillen.github.io/chess-frontend/` (frontend on GH Pages)
