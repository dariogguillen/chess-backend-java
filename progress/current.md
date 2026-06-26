# Current session

**Status:** closed — no active feature. Feature `game-review` (priority 23.94)
closed on **2026-06-26** with reviewer approval and explicit user sign-off.
**The profile-support arc is COMPLETE** (23.91 → 23.92 → 23.93 → 23.94).
See `progress/history.md` for the full entries.

The user is **deploying the latest changes** after this close — see "Deploy
readiness" below.

---

## Project state

- **44 done, 0 in_progress, 1 pending** in `feature_list.json`.
- **Production deploy:** <https://chess-backend.duckdns.org>
- **Frontend deploy:** <https://chess-frontend-52i.pages.dev/>

### Pending feature

- **24 `random-matchmaking`** — Redis-backed matchmaking queue. **Deferred.**

---

## Deploy readiness (user is deploying now)

**Deploy = `git push origin main` → GitHub Actions (`deploy.yml`).** Before/while
pushing:

1. **Uncommitted features still to `git add` + commit** (the user batches commits;
   23.8–23.92 were already committed earlier):
   - **me-stats (23.93)** — `persistence/UserGameStatsView.java`,
     `web/me/{MyStatsResponse,MeStatsController,MeStatsIT}.java`,
     `notes/23.93-me-stats.md` + mods to `GameHistoryRepository`,
     `GameHistoryService`, `docs/architecture.md`, `README.md`.
   - **game-review (23.94)** — `web/me/{MyGameDetail,GameReviewIT}.java`,
     `notes/23.94-game-review.md` + mods to `GameHistoryRepository`,
     `GameHistoryService`, `MyGamesController`, `docs/architecture.md`, `README.md`.
   Neither adds a migration.
2. **Flyway migrations that run on this deploy** (if 23.8/23.92 weren't yet
   deployed): **V3** (friends) then **V4** (game result). They run automatically
   against prod RDS at app boot, in order. V4's backfill UPDATE touches existing
   `games` rows (derives result from final_fen; ABANDONED → NULL).
3. **Infra is in "apagar bajo demanda" mode** — prod EC2 + RDS may be STOPPED.
   To deploy against live infra: **start RDS first, wait for `available`, then
   start EC2** (the app comes up via Docker restart policy). The swap + `-Xmx320m`
   cap are already applied so the t3.micro won't livelock. The `scp` of
   `docker-compose.prod.yml` (feature 26) syncs on deploy. See
   `[[project-infra-on-demand-mode]]`.
4. **New surfaces going live** with this batch: friends (23.8), invitations +
   `/user/queue/invitations` STOMP destination (23.9), profile edit (23.91),
   game result (23.92), `/api/me/stats` (23.93), `/api/me/games/{id}` (23.94).
   All additive; no breaking contract change. Frontend coordinates the new UIs.

---

## Roadmap

- **Profile-support arc: COMPLETE** (23.91 edit, 23.92 result, 23.93 stats,
  23.94 review). The frontend can now build the full profile page.
- **Next backlog** (none promoted; pick at next session): `random-matchmaking`
  (24, deferred); profile follow-ups (avatar upload — needs object storage;
  public profile `GET /api/users/{id}`); Bot Phase 2 (MultiPV-4 + randomized
  weakness); the Lichess-API "section to learn".

---

## Carried ops state — see `[[project-infra-on-demand-mode]]`

- On-demand mode; t3.micro needs swap (applied 2026-06-23) + `-Xmx320m` (in the
  compose). Deploy = push to main. RDS auto-restarts after 7 days of being
  stopped → re-stop manually.

---

## Leader notes for the next session

- Repo at **44/0/1**, profile-support arc done. No active feature. The user was
  deploying the 23.93 + 23.94 (and any not-yet-deployed 23.8/23.92) changes at
  close — **confirm the deploy went green** (GitHub Actions + the post-deploy
  smoke test on `/api/health`) and that the V3/V4 migrations applied cleanly
  before starting new work.
- Per `[[feedback-user-handles-commits]]`: the user handles commits. me-stats +
  game-review were uncommitted at close.
- **Carried non-blocking follow-ups**:
  - 23.9: `InvitationService.cancel` skips the 403 when the room already TTL'd
    out (harmless).
  - Terminal-path IT discipline: gate on the Postgres archive
    (`untilAsserted` + `findById(...).isPresent()`), never Redis status alone;
    Mockito `timeout(...)` for broadcasts. (TimeControlIT flake fixed in 23.92.)
- **History gap still open:** feature 26 `deploy-config-sync` (done 2026-06-22)
  has no `progress/history.md` entry. Low priority.
