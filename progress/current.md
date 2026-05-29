# Current session

**Status:** closed — no active feature. The auth bundle (features
16–20) closed on 2026-05-29. See `progress/history.md` for the
feature 20 entry and the "Auth bundle complete" milestone marker
below it.

---

## Project state

- **29 done, 0 in_progress, 0 pending** in `feature_list.json`.
- **Production deploy:** <https://chess-backend.duckdns.org>
- **Frontend deploy:** <https://chess-frontend-52i.pages.dev/>

The portfolio plan (original 14 features) closed on 2026-05-25.
The maintenance reopen for `cors-cloudflare-origin` (feature 15)
landed the same day. The auth bundle (16–20) opened 2026-05-27,
closed 2026-05-29. The repo returns to maintenance mode.

---

## Pre-deploy operator checklist (auth bundle)

Required before deploying the auth bundle to production:

- **`AUTH_JWT_SECRET`** — set on EC2 (`/opt/chess/.env`). Must be
  ≥ 32 bytes (HS256). Without it, boot fails fast with
  `BeanCreationException` because `AuthProperties.Jwt`'s compact
  constructor rejects empty/short secrets. Generate with
  `openssl rand -base64 48` or equivalent.
- **`GOOGLE_OAUTH_CLIENT_ID`** and **`GOOGLE_OAUTH_CLIENT_SECRET`** —
  set on EC2. Either real Google Cloud Console credentials OR
  placeholder values (the OAuth flow only fails at runtime if
  invoked, not at boot). Real values required for actual Google
  sign-in to work; without them, the `/oauth2/authorization/google`
  endpoint returns an error if a client tries it.
- **`AUTH_OAUTH_FRONTEND_REDIRECT_BASE`** — set to
  `https://chess-frontend-52i.pages.dev` on EC2. Default
  (`http://localhost:5173`) only matters for local dev.

Carry-over operator follow-ups (from earlier sessions, still apply):

- **Rotate the RDS master password.** Especially pressing before
  user accounts exist in production.
- **Configure branch protection on `main`** via the GitHub UI.
- **Replace the static "212 tests" claim in `README.md`** with a
  dynamic count or remove it — it drifts on every future feature.

---

## Guest-play impact (deploy safety)

The auth bundle is **additive** for guest play. After deploy:

- All existing REST endpoints used by the frontend stay open:
  `POST /api/rooms`, `POST /api/rooms/{id}/join`,
  `GET /api/rooms/{id}`, `POST /api/games`, `POST /api/games/{id}/moves`,
  `GET /api/games/{id}`, `GET /api/players/{id}/games` — all in
  `SecurityConfig`'s `permitAll` matcher. Bearer header is not
  required and not rejected when absent.
- STOMP/WebSocket anonymous flow preserved by design (bundle
  decision 7). The `StompAuthInterceptor` from feature 20 NEVER
  rejects a CONNECT for identity reasons. Existing 18
  WebSocket-IT cases across 5 IT classes stayed green without
  modification.
- JSON wire-format unchanged. The `PlayerView(id, displayName)`
  refactor of `GameStateResponse` and `RoomJoinedEvent` produces
  byte-identical JSON to the pre-bundle `Player(id, displayName)`
  serialisation (`Player.userId` was a NEW field added by feature
  19 and is intentionally never exposed).
- Edge case in feature 20's pin-on-first-use: if a guest somehow
  sends two STOMP SENDs with different `playerId` values in the
  same WebSocket session, the second is rejected with a STOMP
  ERROR frame. The session stays open (no force-disconnect).
  Unlikely to bite a normal frontend, but possible.

The deploy itself triggers Flyway migration `V2` automatically at
boot (creates `users` table + adds nullable FK columns
`white_user_id` / `black_user_id` to `games`). Forward-only;
reversible only via a manual `V3` migration.

---

## Future scope candidates (not yet promoted to `feature_list.json`)

Surfaced during bundle planning and close discussions. None are
committed scope; written here so the next session can pick one up
if the user chooses. Promote to `feature_list.json` only when the
user explicitly chooses one and the leader writes a plan.

### Auth bundle extensions

- **Refresh tokens with rotation** — the bundle ships a single
  7-day JWT. Production-grade auth eventually needs a refresh
  flow with rotation + revocation list (Redis JWT blacklist).
- **Email verification + password reset** — `/api/auth/forgot`
  + email send + `/api/auth/reset/{token}`. Requires SMTP
  config or a transactional email provider.
- **Account linking** — allow the same `User` row to have both
  `passwordHash` and `googleSub`. Feature 18 explicitly
  surfaces email collisions as `#error=email_taken`; account
  linking would replace that with a "merge identities" flow.
- **Claim flow for anonymous games** — at first login, offer
  to claim the games created under the current `X-Player-Id`.
  Bundle deliberately deferred this; the data model supports
  it (the `games.{white,black}_player_id` audit columns can
  be cross-referenced).
- **Rate limiting** on `/api/auth/login` and `/api/games/{id}/moves`
  (token bucket per IP and per `X-Player-Id` / `User.id`).
- **Multi-provider OAuth** — Apple, GitHub. The `oauth2Login`
  DSL extends naturally; the success handler already handles
  the "find-or-create by provider sub" shape.

### Operational hardening

- Replace the static "212 tests" README claim with a dynamic
  count or remove it (carryover operator follow-up).
- Rotate the RDS master password (carryover).
- Configure branch protection on `main` (carryover).
- Sanitise pre-2026-05-29 operator follow-ups from the Google
  OAuth setup into a `docs/auth-runbook.md`.

### High-portfolio-signal additions

- **Metrics + Prometheus + Grafana.** `docs/architecture.md`
  already names this as a future step. Hosted Grafana free tier
  or self-hosted on EC2.
- **Distributed tracing (OpenTelemetry → Jaeger / Tempo).**
- **Game detail / replay endpoint.**

### Resilience / scale

- **Persist grace timers to Redis with restart-time recovery
  sweep.** Feature 11 explicitly left this out of scope.
- **Redis-side atomicity (Lua / WATCH-MULTI-EXEC).** Replaces
  the process-local `StripedKeyLock` so the backend can scale
  to multiple instances.

### Domain features (product-side, lower technical signal)

- ELO ratings, move clocks, tournament structure.

### Maintenance pure

- Spring Boot, chesslib, springdoc dep bumps.
- Postgres RDS minor version upgrade.

---

## Leader notes for the next session

- The repo is in maintenance mode by default. `feature_list.json`
  is at 29/0/0. If the user says "vamos con el siguiente" without
  context, confirm against the file and consult this list before
  assuming scope.
- New features get a fresh `feature_list.json` entry at priority
  ≥ 21, full harness cycle (leader plan → implementer → reviewer →
  user OK → feature note → history entry). Diff size is irrelevant.
- Per [[feedback-flag-untracked-files-at-close]]: when reporting
  feature closure, explicitly list untracked files in `git status`
  and warn the user to `git add` them before committing.
- Per [[project-portfolio-complete]] (updated after this bundle):
  the portfolio shipped 14 features in the original plan plus a
  maintenance reopen (15) plus the auth bundle (16–20). Total: 29
  features. Status quo for future work is to open new entries
  individually unless the user explicitly proposes a new multi-feature
  bundle.
