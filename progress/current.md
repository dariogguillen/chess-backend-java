# Current session

**Status:** closed — no active feature. Feature `time-control`
(priority 22) closed on 2026-05-29 with reviewer approval and explicit
user sign-off. See `progress/history.md` for the entry.

---

## Project state

- **32 done, 0 in_progress, 3 pending** in `feature_list.json`.
- **Production deploy:** <https://chess-backend.duckdns.org>
- **Frontend deploy:** <https://chess-frontend-52i.pages.dev/>

### Pending features (lowest priority first)

- **23 `bot-opponent`** — play vs Stockfish (subprocess + UCI). Touches
  the Dockerfile (bundle the Stockfish binary) and the move/broadcast
  pipeline; the heaviest of the remaining product features.
- **24 `random-matchmaking`** — Redis-backed matchmaking queue. The
  acceptance notes it partitions by `TimeControl` once feature 22 ships
  (now done), though MVP can ship a single global queue.
- **26 `deploy-config-sync`** — scp `docker-compose.prod.yml` to EC2 on
  every deploy (operational/infra hardening; no application code).

---

## Untracked files from feature 22 (flag for `git add`)

Per [[feedback-flag-untracked-files-at-close]]. No new directories.

- `notes/22-time-control.md`
- `src/main/java/io/github/dariogguillen/chess/domain/TimeControl.java`
- `src/main/java/io/github/dariogguillen/chess/service/ClockTimerManager.java`
- `src/main/java/io/github/dariogguillen/chess/service/GameTimeoutService.java`
- `src/main/java/io/github/dariogguillen/chess/websocket/GameTimedOutEvent.java`
- `src/test/java/io/github/dariogguillen/chess/domain/GameStatusTest.java`
- `src/test/java/io/github/dariogguillen/chess/domain/TimeControlTest.java`
- `src/test/java/io/github/dariogguillen/chess/service/GameServiceClockTest.java`
- `src/test/java/io/github/dariogguillen/chess/websocket/TimeControlIT.java`

Modified tracked files (commit alongside): `domain/{Game,GameStatus,Room}.java`,
`service/{GameService,RoomService}.java`, `web/game/{GameController,GameStateResponse,PlayerGameSummary}.java`,
`web/me/MyGameSummary.java`, `web/room/{CreateRoomRequest,RoomController}.java`,
`websocket/{GameStateEvent,MoveEvent}.java`, the extended test classes,
`docs/architecture.md`, `feature_list.json`, `progress/history.md`.

---

## Leader notes for the next session

- Repo is in extension mode. `feature_list.json` is at 32/0/3. Next
  pending product feature is **23 `bot-opponent`**; **24
  `random-matchmaking`** and **26 `deploy-config-sync`** also pending.
- New features get a full harness cycle (leader plan → implementer →
  reviewer → user OK → feature note → history entry). Diff size is
  irrelevant.
- Sub-agent dispatch: as of 2026-05-29 the `.claude/agents/*.md` files
  carry YAML frontmatter, so `Agent(subagent_type: "implementer" |
  "reviewer")` resolves directly — no more `general-purpose` fallback.
  The registry is read at session start; if a fresh session ever reports
  the type missing, the file frontmatter is the thing to check.
- Per [[feedback-user-handles-commits]]: never `git add` / `git commit`
  here. Tell delegated implementer agents this explicitly (it overrides
  the implementer role file's "make small commits" line).
- Cross-feature note for whoever picks 24 (`random-matchmaking`): feature
  22 shipped `TimeControl`, so the queue *can* now partition by time
  control — but the acceptance allows an MVP single global queue first.
