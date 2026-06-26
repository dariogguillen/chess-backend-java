# Current session

**Status:** closed — no active feature. Feature `game-result-persistence`
(priority 23.92) closed on **2026-06-26** with reviewer approval (pass 2) and
explicit user sign-off. See `progress/history.md` for the full entry.

---

## Project state

- **42 done, 0 in_progress, 1 pending** in `feature_list.json`.
- **Production deploy:** <https://chess-backend.duckdns.org>
- **Frontend deploy:** <https://chess-frontend-52i.pages.dev/>

### Pending feature

- **24 `random-matchmaking`** — Redis-backed matchmaking queue. **Deferred**;
  the profile-support arc comes first (see roadmap).

---

## Uncommitted work — flag for `git add` (FOUR features)

The user batches/handles commits. Uncommitted ahead of
`3daea6f feat: sync docker compose file to EC2 on deploy`, oldest first:

- **friends-list (23.8)** — incl. `db/migration/V3`.
- **direct-invitations (23.9)** — incl. the feature-20 `StompAuthInterceptor` fix.
- **profile-edit (23.91)**.
- **game-result-persistence (23.92)** — 6 new files (`domain/GameResult.java`,
  `db/migration/V4__add_game_result.sql`, tests `domain/GameResultTest.java`,
  `service/GameServiceIT.java`, `persistence/GameResultBackfillIT.java`,
  `notes/23.92-game-result-persistence.md`) + mods to `domain/Game.java`, the four
  terminal services, the persistence/projection layer, the two history DTOs/
  controllers, `docs/architecture.md`, and several ITs.

Per-feature file lists are in the `history.md` entries. On the eventual push the
migrations run against prod RDS in order: **V3** (friends) then **V4** (result).

---

## Roadmap — the profile-support arc (user breakdown, 2026-06-24/26)

1. profile-edit (done, 23.91)
2. game-result-persistence (done, 23.92)
3. **me-stats (NEXT, not yet an entry)** — `GET /api/me/stats`: a JPQL aggregate
   over `games.result` cross-referenced with the authenticated user's side
   (white_user_id vs black_user_id) → wins / losses / draws, total games, win %.
   The `result` column (23.92) is in place; legacy rows with NULL result (old
   ABANDONED) should be counted as a separate "unknown" bucket or excluded —
   decide at plan time. Likely a `GET /api/me/stats` returning a small stats DTO;
   no migration. Promote as a new entry (priority between 23.92 and 24).
4. **game-review (independent)** — `GET /api/me/games/{id}` returning the full
   move list (the `moves` table already stores them) so the frontend replays a
   game. First verify whether a game-detail-with-moves endpoint already exists.

Avatar upload + public profile of another user are later follow-ups. The frontend
can build the read-only profile + history (now incl. result) against existing
endpoints today.

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
- **Deploying the uncommitted arc:** V3 (friends) then V4 (result) run against
  prod RDS on the next push; the new friends/invitations/profile/result surfaces
  + the `/user/queue/invitations` STOMP destination go live then.

---

## Leader notes for the next session

- Repo at **42/0/1**. Next per the roadmap is **me-stats** (not yet an entry —
  promote it, priority between 23.92 and 24, full harness cycle). Scope sketched
  above; the only real decision is how to treat NULL-result legacy rows in the
  aggregate (unknown bucket vs excluded).
- Per `[[feedback-user-handles-commits]]`: the user handles commits. FOUR
  features (23.8 + 23.9 + 23.91 + 23.92) are uncommitted — flagged above. This
  set is getting large; worth nudging the user to commit/push soon so prod
  catches up and the V3/V4 migrations land.
- **RESOLVED this cycle**: the long-carried `TimeControlIT` async-IT flake (the
  one that flaked in 23.7/23.9 and bit the 23.92 review) is now FIXED — the gate
  waits for the archive, not just the Redis status. The general lesson stands for
  any new terminal-path IT: gate on the Postgres archive (`untilAsserted` +
  `historyRepository.findById(...).isPresent()`), never on the Redis status alone,
  and use Mockito `timeout(...)` for broadcast verifies.
- **Carried non-blocking follow-up from 23.9**: `InvitationService.cancel` skips
  the 403 when the room already TTL'd out. Harmless.
- **History gap still open:** feature 26 `deploy-config-sync` (done 2026-06-22)
  has no `progress/history.md` entry. Low priority.
