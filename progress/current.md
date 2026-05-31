# Current session

**Status:** closed — no active feature. Feature `bot-difficulty`
(priority 23.5) closed on 2026-05-30 with reviewer approval and explicit
user sign-off. See `progress/history.md` for the entry.

---

## Project state

- **36 done, 0 in_progress, 2 pending** in `feature_list.json`.
- **Production deploy:** <https://chess-backend.duckdns.org>
- **Frontend deploy:** <https://chess-frontend-52i.pages.dev/>

### Pending features (lowest priority first)

- **24 `random-matchmaking`** — Redis-backed matchmaking queue. **The
  user deferred this** (2026-05-30) behind a friends-list + invitations
  pair (see roadmap below); it stays `pending` but is intentionally not
  next in intent.
- **26 `deploy-config-sync`** — scp `docker-compose.prod.yml` to EC2 on
  every deploy (operational/infra; no application code).

---

## ⚠️ Uncommitted work (IMPORTANT — two features intermixed)

Last commit: `27344cd feat: room access token` (feature 22.7).
**Features 23 (`bot-opponent`) and 23.5 (`bot-difficulty`) are both
uncommitted** and intermixed in the working tree. The user planned to
commit them together.

Shared/modified files touched by BOTH 23 and 23.5: `domain/Game.java`,
`service/RoomService.java`, `web/room/CreateRoomRequest.java`,
`web/room/RoomController.java`, `src/main/resources/application.yml`,
`docs/architecture.md`.

Untracked (new) files (mix of 23 and 23.5):
- `notes/23-bot-opponent.md`, `notes/23.5-bot-difficulty.md`
- `src/main/java/.../config/BotConfig.java`, `config/BotProperties.java`
- `src/main/java/.../domain/OpponentKind.java`
- `src/main/java/.../service/bot/` (whole dir)
- `src/main/java/.../websocket/GameEngineFailedEvent.java`
- `src/test/java/.../service/bot/` (whole dir)
- `src/test/java/.../websocket/BotGameIT.java`

Suggested commit (both features together):
`feat(bot): play vs Stockfish (UCI subprocess) with per-room Elo strength`.

---

## Carried deploy actions

- **22.7 `room-access-tokens`** still needs a coordinated frontend deploy
  (the live frontend joins token-less; backend is in-flight-safe via the
  null-token legacy rule).
- **23 `bot-opponent`**: the deployed Docker image bundles Stockfish —
  verify the install path matches `chess.bot.engine-path` after the build.

---

## Roadmap (user re-prioritisation, 2026-05-30)

Order the user wants: **bot-difficulty (done)** → **friends list** →
**direct invitations** (layered on the friends list + `room-access-tokens`
22.7) → **random-matchmaking (24) deferred** until after those. Neither
friends-list nor invitations is promoted to `feature_list.json` yet —
promote when the user picks one up (a new entry, priority < 24, full
harness cycle).

## Other future scope not yet promoted

- **Bot difficulty extensions**: sub-1320 beginner strength via `Skill
  Level`; persisting the bot Elo to Postgres history; labelled Elo presets.
- **Direct invitations to registered users** (the friends-list +
  invitations pair above).

---

## Leader notes for the next session

- Repo is in extension mode. `feature_list.json` is at 36/0/2. Per the
  user's roadmap, the next feature is a **friends list** (not yet an
  entry); `random-matchmaking` (24) is deferred.
- New features get a full harness cycle (leader plan → implementer →
  reviewer → user OK → feature note → history entry). Diff size is
  irrelevant.
- Sub-agent dispatch works directly: `Agent(subagent_type: "implementer"
  | "reviewer")` resolves (the `.claude/agents/*.md` files carry YAML
  frontmatter as of 2026-05-29).
- Per [[feedback-user-handles-commits]]: never `git add` / `git commit`.
  Note the user has been BATCHING commits — features 23 + 23.5 closed in
  the harness (status done) but sit uncommitted together; when reporting
  closure, keep flagging the uncommitted set so it doesn't grow unbounded.
- When the user picks up "friends list": it's a new domain area (a
  friendship/relationship between `users`), needs a Flyway migration
  (`V3`), and is the prerequisite the user named for direct invitations.
  Plan it fresh; nothing in the repo models friendships yet.
