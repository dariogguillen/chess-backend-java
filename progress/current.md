# Current session

**Status:** closed — no active feature. Feature `spectators-in-room`
(priority 22.5) closed on 2026-05-29 with reviewer approval and explicit
user sign-off. See `progress/history.md` for the entry.

---

## Project state

- **33 done, 0 in_progress, 4 pending** in `feature_list.json`.
- **Production deploy:** <https://chess-backend.duckdns.org>
- **Frontend deploy:** <https://chess-frontend-52i.pages.dev/>

### Pending features (lowest priority first)

- **22.7 `room-access-tokens`** — separate join token so a shared "watch"
  link cannot be used to grab the player slot (the play-link vs
  watch-link design). Surfaced while planning `spectators-in-room`.
  Direct invitations to registered users are a later feature on top of
  this. Note: this one CHANGES the join contract (`POST
  /api/rooms/{id}/join` would require the token), so it needs a documented
  frontend-coordination strategy.
- **23 `bot-opponent`** — play vs Stockfish (subprocess + UCI); touches
  the Dockerfile.
- **24 `random-matchmaking`** — Redis-backed matchmaking queue; can now
  partition by `TimeControl` (feature 22 shipped) but MVP allows a single
  global queue.
- **26 `deploy-config-sync`** — scp `docker-compose.prod.yml` to EC2 on
  every deploy (operational/infra; no application code).

---

## Untracked files from feature 22.5 (flag for `git add`)

Per [[feedback-flag-untracked-files-at-close]]. No new directories.

- `notes/22.5-spectators-in-room.md`

Modified tracked files (commit alongside): `websocket/ViewerCountTracker.java`,
`websocket/ViewerCountEvent.java`, `websocket/RoomEvent.java`,
`websocket/GameStateEvent.java`, `websocket/PlayerSessionTracker.java`,
`src/test/java/.../websocket/ViewerCountIT.java`, `docs/architecture.md`,
`feature_list.json`, `progress/history.md`.

---

## Leader notes for the next session

- Repo is in extension mode. `feature_list.json` is at 33/0/4. Next
  pending feature by priority is **22.7 `room-access-tokens`** (the
  natural follow-up to `spectators-in-room`); then 23 / 24 / 26.
- New features get a full harness cycle (leader plan → implementer →
  reviewer → user OK → feature note → history entry). Diff size is
  irrelevant.
- Sub-agent dispatch works directly now: `Agent(subagent_type:
  "implementer" | "reviewer")` resolves (the `.claude/agents/*.md` files
  carry YAML frontmatter as of 2026-05-29). No `general-purpose` fallback
  needed.
- Per [[feedback-user-handles-commits]]: never `git add` / `git commit`
  here. Tell delegated implementer agents this explicitly (it overrides
  the implementer role file's "make small commits" line).
- Context for whoever picks 22.7: the spectator flow now relies on the
  `roomId` being shareable for watching. `room-access-tokens` must keep
  the `roomId` usable for `GET /api/rooms/{id}` + `SUBSCRIBE
  /topic/rooms/{roomId}` (watch) while gating only the join behind the new
  token. Decide token transport (header vs body), the new 403 error code,
  and the frontend-migration approach (the live join sends no token
  today).
