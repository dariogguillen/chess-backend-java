# Current session

**Status:** closed — no active feature. Feature `color-selection`
(priority 21) closed on 2026-05-29 with reviewer approval and explicit
user sign-off. See `progress/history.md` for the entry.

---

## Project state

- **31 done, 0 in_progress, 4 pending** in `feature_list.json`.
- **Production deploy:** <https://chess-backend.duckdns.org>
- **Frontend deploy:** <https://chess-frontend-52i.pages.dev/>

### Pending features (lowest priority first)

- **22 `time-control`** — server-authoritative clock + auto-flagging.
- **23 `bot-opponent`** — play vs Stockfish (subprocess + UCI).
- **24 `random-matchmaking`** — Redis-backed matchmaking queue.
- **26 `deploy-config-sync`** — scp `docker-compose.prod.yml` to EC2 on
  every deploy (operational hardening surfaced by feature 25). Note this
  is operational/infra, not application code; the next *product* feature
  in priority order is 22.

---

## Untracked files from feature 21 (flag for `git add`)

Per [[feedback-flag-untracked-files-at-close]] — these were reported to
the user at close. No new directories were created.

- `notes/21-color-selection.md`
- `src/main/java/io/github/dariogguillen/chess/domain/SidePreference.java`
- `src/main/java/io/github/dariogguillen/chess/service/RandomSideChooser.java`
- `src/test/java/io/github/dariogguillen/chess/service/RoomServiceTest.java`

Tracked-but-modified files (commit alongside) include `Room.java`,
`RoomService.java`, the four `web/room/` files, the extended test
classes, `docs/architecture.md`, `feature_list.json`, and
`progress/history.md`.

---

## Leader notes for the next session

- Repo is in maintenance/extension mode. `feature_list.json` is at
  31/0/4. The next pending product feature is **22 `time-control`**;
  **26 `deploy-config-sync`** is infra hardening that can be picked
  independently.
- New features get a full harness cycle (leader plan → implementer →
  reviewer → user OK → feature note → history entry). Diff size is
  irrelevant.
- Sub-agent dispatch: as of 2026-05-29 the `.claude/agents/*.md` files
  (leader, implementer, reviewer) gained YAML frontmatter (`name` +
  `description`) so Claude Code registers them as invocable
  `subagent_type`s. **The registry is read at session start**, so the
  first session after this change must be a *fresh* one for
  `Agent(subagent_type: "implementer" | "reviewer")` to resolve — it was
  added mid-session and did not take effect in that session. If a session
  still reports `Agent type 'implementer' not found`, fall back to
  launching a `general-purpose` agent told to adopt the role by reading
  `.claude/agents/{implementer,reviewer}.md`.
- Per [[feedback-user-handles-commits]]: never `git add` / `git commit`
  here — the user commits. Tell delegated implementer agents this
  explicitly (it overrides the implementer role file's "make small
  commits" instruction).
- The "Future scope candidates" list from the auth-bundle close still
  lives in `progress/history.md` and earlier `current.md` snapshots if
  the user wants to promote one.
