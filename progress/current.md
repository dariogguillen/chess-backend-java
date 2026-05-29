# Current session

**Status:** closed — no active feature. Feature `room-access-tokens`
(priority 22.7) closed on 2026-05-29 with reviewer approval and explicit
user sign-off. See `progress/history.md` for the entry.

---

## Project state

- **34 done, 0 in_progress, 3 pending** in `feature_list.json`.
- **Production deploy:** <https://chess-backend.duckdns.org>
- **Frontend deploy:** <https://chess-frontend-52i.pages.dev/>

### Pending features (lowest priority first)

- **23 `bot-opponent`** — play vs Stockfish (subprocess + UCI); touches
  the Dockerfile. Heaviest of the remaining product features.
- **24 `random-matchmaking`** — Redis-backed matchmaking queue; can now
  partition by `TimeControl` (feature 22) but MVP allows a single global
  queue.
- **26 `deploy-config-sync`** — scp `docker-compose.prod.yml` to EC2 on
  every deploy (operational/infra; no application code).

---

## ⚠️ Pending deploy action for feature 22.7 (room-access-tokens)

`room-access-tokens` **requires a coordinated frontend deploy**. The
backend is safe for in-flight rooms (null-token legacy rule), but the live
frontend joins token-less and will 403 against any room created
post-deploy. Before/with deploying this backend, the frontend must ship:
read `joinToken` from the create response, send it on join, build the
play-link (with token) vs watch-link (roomId only). Sequence the two
deploys together.

---

## Untracked files from feature 22.7 (flag for `git add`)

Per [[feedback-flag-untracked-files-at-close]]. No new directories.

- `notes/22.7-room-access-tokens.md`
- `src/main/java/io/github/dariogguillen/chess/exception/InvalidJoinTokenException.java`

Modified tracked files (commit alongside): `domain/Room.java`,
`exception/{GlobalExceptionHandler,ErrorResponse}.java`,
`service/RoomService.java`, `web/room/{JoinRoomRequest,RoomResponse,RoomController}.java`,
the ~14 test/IT classes listed in the history entry, `docs/architecture.md`,
`README.md`, `feature_list.json`, `progress/history.md`.

---

## Future scope not yet promoted to feature_list.json

- **Direct invitations to registered users** — layered on top of
  `room-access-tokens` (22.7): now that accounts exist, a creator could
  invite a registered user to play (or to watch) directly, instead of
  sharing a link. Surfaced during the spectators/access-tokens discussion;
  deliberately deferred. Promote only when the user picks it up.

---

## Leader notes for the next session

- Repo is in extension mode. `feature_list.json` is at 34/0/3. Next
  pending feature by priority is **23 `bot-opponent`**; then 24 / 26.
- New features get a full harness cycle (leader plan → implementer →
  reviewer → user OK → feature note → history entry). Diff size is
  irrelevant.
- Sub-agent dispatch works directly: `Agent(subagent_type: "implementer"
  | "reviewer")` resolves (the `.claude/agents/*.md` files carry YAML
  frontmatter as of 2026-05-29).
- Per [[feedback-user-handles-commits]]: never `git add` / `git commit`
  here. Tell delegated implementer agents this explicitly (it overrides
  the implementer role file's "make small commits" line).
- The spectator + access-token pair (22.5 / 22.7) completed the
  "invite friends to watch, protect the player slot" flow the user asked
  for. `bot-opponent` (23) is unrelated product scope; `random-matchmaking`
  (24) reuses the room/matchmaking surface and can partition by the
  `TimeControl` shipped in feature 22.
