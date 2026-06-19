# Current session

**Status:** closed — no active feature. Feature
`bot-strength-fairy-stockfish` (priority 23.7) closed on 2026-05-30 with
reviewer approval and explicit user sign-off. Session ended for the day.
See `progress/history.md` for the entry.

---

## Project state

- **37 done, 0 in_progress, 2 pending** in `feature_list.json`.
- **Production deploy:** <https://chess-backend.duckdns.org>
- **Frontend deploy:** <https://chess-frontend-52i.pages.dev/>

### Pending features (lowest priority first)

- **24 `random-matchmaking`** — Redis-backed matchmaking queue.
  **Deferred by the user** behind the friends-list + invitations pair
  (see roadmap). Stays `pending`, not next in intent.
- **26 `deploy-config-sync`** — scp `docker-compose.prod.yml` to EC2 on
  every deploy (operational/infra; no application code).

---

## Commit state — 23.7 staged, ready for its own commit

- **23 (`bot-opponent`) + 23.5 (`bot-difficulty`)**: already committed by
  the user in `7222479 feat: bot implementation stockfish`.
- **23.7 (`bot-strength-fairy-stockfish`)**: staged (the leader ran
  `git add -A`), working tree clean — a clean independent commit (23
  files). The user just needs to commit it.

Suggested commit message:
`feat(bot): Lichess-style Elo strength via Fairy-Stockfish (skill+depth)`

---

## Carried deploy actions

- **22.7 `room-access-tokens`** still needs a coordinated frontend deploy
  (live frontend joins token-less; backend in-flight-safe via null-token
  legacy rule).
- **Bot (23 / 23.7)**: the deployed Docker image now bundles
  **Fairy-Stockfish** (pinned `fairy_sf_14`, checksum-verified) at
  `/usr/local/bin/fairy-stockfish` — NOT the apt `stockfish` anymore.
  Verify the path matches `chess.bot.engine-path` after the image build.

---

## Roadmap (user re-prioritisation, 2026-05-30)

Order the user wants: bot work (done: 23, 23.5, 23.7) → **friends list**
→ **direct invitations** (layered on friends + `room-access-tokens` 22.7)
→ **random-matchmaking (24) deferred** until after those. Friends-list
and invitations are NOT yet entries in `feature_list.json` — promote when
the user picks one up (new entry, priority < 24, full harness cycle).

## Other future scope not yet promoted

- **Bot Phase 2**: MultiPV-4 + randomized weakness (the full Lichess
  model) for more human-feeling errors.
- **"Section to learn" using the Lichess API** (user's idea, 2026-05-30)
  — a future feature, unrelated to the engine work.

---

## Leader notes for the next session

- Repo is in extension mode. `feature_list.json` is at 37/0/2. Per the
  user's roadmap, the next feature is a **friends list** (not yet an
  entry); `random-matchmaking` (24) is deferred.
- New features get a full harness cycle (leader plan → implementer →
  reviewer → user OK → feature note → history entry).
- Sub-agent dispatch works directly: `Agent(subagent_type: "implementer"
  | "reviewer")` resolves (frontmatter added 2026-05-29).
- Per [[feedback-user-handles-commits]]: the user handles commits, BUT
  has twice explicitly asked the leader to run `git add` for the
  accumulating bot bundle (23/23.5/23.7) — the index currently holds all
  three. Keep flagging the uncommitted set at every close so it doesn't
  grow unbounded; the user is batching the bot work into one commit.
- **Async-IT flakiness pattern to watch** (bit us in 23.7): any IT that
  `await()`s on a Redis-state condition and then asserts a Postgres
  archive or a `verify(messagingTemplate…)` OUTSIDE the await is racy,
  because the terminal-action services (GameAbandonService /
  GameTimeoutService / BotMoveService.failGame) do compute(Redis) →
  archive(Postgres) → broadcast on an async thread. Put the archive
  assertion inside `untilAsserted` and use Mockito `timeout()` for
  broadcast verifies. Worth a sweep of the other terminal-path ITs if
  flakiness recurs.
- When the user picks up "friends list": new domain area (a
  friendship/relationship between `users`), needs a Flyway `V3`
  migration; it's the prerequisite the user named for direct invitations.
  Nothing in the repo models friendships yet — plan it fresh.
