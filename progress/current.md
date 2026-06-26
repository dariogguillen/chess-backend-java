# Current session

**Status:** closed — no active feature. Feature `profile-edit` (priority 23.91)
closed on **2026-06-26** with reviewer approval and explicit user sign-off.
See `progress/history.md` for the full entry.

---

## Project state

- **41 done, 0 in_progress, 1 pending** in `feature_list.json`.
- **Production deploy:** <https://chess-backend.duckdns.org>
- **Frontend deploy:** <https://chess-frontend-52i.pages.dev/>

### Pending feature

- **24 `random-matchmaking`** — Redis-backed matchmaking queue. **Deferred** by
  the user; NOT auto-next (the profile-support arc comes first; see roadmap).

---

## Uncommitted work — flag for `git add` (THREE features)

The user batches/handles commits. Currently uncommitted ahead of
`3daea6f feat: sync docker compose file to EC2 on deploy`:

- **friends-list (23.8)** — 22 new files + mods (incl. `db/migration/V3`).
- **direct-invitations (23.9)** — 15 new files + mods (incl. the feature-20
  `StompAuthInterceptor` fix, `config/{WebSocketConfig,RedisConfig}`).
- **profile-edit (23.91)** — 5 new files (`service/ProfileService.java`,
  `web/auth/{UpdateProfileRequest,ChangePasswordRequest,ProfileIT}.java`,
  `notes/23.91-profile-edit.md`) + mods to `domain/User.java`,
  `web/auth/{MeResponse,MeController}.java`, `service/auth/AuthService.java`,
  `docs/architecture.md`, `README.md`.

Full per-feature file lists are in the `history.md` entries. When the user
pushes, the **V3 migration (friends)** runs against prod RDS; 23.9 + 23.91 add
no migration.

---

## Roadmap — the profile-support arc (user breakdown, 2026-06-24/26)

After finding the win/loss gap (the archived `games` table has no winner/result
column — see the 23.91 history entry for the full diagnosis), the user split
profile-support into small features:

1. **profile-edit (done, 23.91)** — createdAt on /api/me + PATCH rename + PUT password.
2. **game-result-persistence (NEXT, not yet an entry)** — the foundation:
   - V4 migration adding a `result` column to `games` (e.g. `WHITE_WINS` /
     `BLACK_WINS` / `DRAW`, or a nullable `winner_side`).
   - Thread the already-computed `winnerId` into the archive at all FOUR terminal
     paths — `GameService.applyMove` (checkmate/draw), `GameAbandonService`,
     `GameTimeoutService`, `BotMoveService` — by adding the result to the `Game`
     domain + `GameEntity` so `mapper.toEntity` persists it (today the winner is
     broadcast on the STOMP events but dropped at archive).
   - Backfill existing rows: derive from `final_fen` active color for
     CHECKMATE/TIMEOUT, DRAW/STALEMATE → draw; ABANDONED old rows are
     unrecoverable → leave `unknown`/null (acceptable, little real prod data).
   - Expose `result` in the `/api/me/games` summary (`MyGameSummary`).
3. **me-stats (after #2)** — `GET /api/me/stats`: JPQL aggregate of W/L/D, total
   games, win %, maybe by-side/by-status breakdown.
4. **game-review (independent)** — `GET /api/me/games/{id}` returning the full
   move list (the `moves` table already stores them) so the frontend replays a
   game. First verify whether a game-detail-with-moves endpoint already exists.

Avatar upload + public profile of another user (`GET /api/users/{id}`) are
later follow-ups (need object storage / a new public endpoint). The frontend can
build the read-only profile today against existing endpoints.

### Other future scope not yet promoted

- **Bot Phase 2**: MultiPV-4 + randomized weakness (full Lichess model).
- **"Section to learn" using the Lichess API** (user's idea, 2026-05-30).

---

## Carried ops state (infra in "apagar bajo demanda" mode)

Full context in `[[project-infra-on-demand-mode]]` memory + history. Live facts:

- **Mode:** EC2 + RDS stopped on demand for credits; t3.micro livelocks without
  swap. Deploy = `git push origin main` → GitHub Actions.
- **Mitigated 2026-06-23**: feature-26 `scp` smoke test passed; **swap (2 GB) +
  `-Xmx320m` cap applied** → the instance stays up. RDS auto-restarts after 7
  days → re-stop manually on next shutdown.
- **Deploying the uncommitted arc:** the V3 (friends) migration runs against
  prod RDS on the next push; the new friends/invitations/profile REST surfaces +
  the `/user/queue/invitations` STOMP destination go live then.

---

## Leader notes for the next session

- Repo at **41/0/1**. Next per the roadmap is **game-result-persistence** (not
  yet an entry — promote it as a new entry, priority between 23.91 and 24, full
  harness cycle). Its scope is sketched above; the interesting part is threading
  the `winnerId` (already computed in all four terminal services) into the
  `Game`/`GameEntity` archive + the V4 migration + the `final_fen` backfill.
- Per `[[feedback-user-handles-commits]]`: the user handles commits. THREE
  features (23.8 + 23.9 + 23.91) are uncommitted — flagged above for `git add`.
- **Carried non-blocking follow-up from 23.9**: `InvitationService.cancel` only
  enforces the 403 `NOT_ROOM_MEMBER` when the room still exists; a non-member
  cancelling a stale invitation whose room TTL'd out skips the 403. Harmless.
- **History gap still open:** feature 26 `deploy-config-sync` (done 2026-06-22)
  never got its own `progress/history.md` entry. Low priority; backfill if a
  clean portfolio narrative matters.
