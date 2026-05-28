# chess-backend-java

[![CI](https://github.com/dariogguillen/chess-backend-java/actions/workflows/ci.yml/badge.svg)](https://github.com/dariogguillen/chess-backend-java/actions/workflows/ci.yml)

Multiplayer chess backend in Java/Spring Boot, deployed to AWS Free Tier. Server-authoritative move validation (chesslib + position-history replay), real-time STOMP broadcasts with sealed-event discriminator, Postgres + Redis split for durable history vs. active state, and a disconnect/reconnect grace-period lifecycle. Built as a portfolio rewrite of an earlier Node prototype, coordinated end-to-end by a leader/implementer/reviewer agent harness.

## Try it live

- **Frontend:** <https://chess-frontend-52i.pages.dev/> — React/Vite SPA on Cloudflare Pages.
- **Backend health:** <https://chess-backend.duckdns.org/api/health> — liveness probe; returns 200 OK with status, version, timestamp.
- **API docs (Swagger UI):** <https://chess-backend.duckdns.org/swagger-ui/index.html> — interactive REST surface.

Open the frontend in two browsers (Firefox + Chrome works well), create a room in one, paste the room code into the other to join, and play.

## What this demonstrates

- **Server-authoritative move validation.** Every move is checked against `chesslib` with the full position history replayed for threefold-repetition and 50-move-rule detection. The client cannot inject an illegal move; the previous Node prototype trusted the browser, this version does not.
- **Real-time broadcasts over STOMP with sealed-event discriminator.** Polymorphic topics (`/topic/rooms/{roomId}`, `/topic/games/{gameId}`) carry a `sealed interface` of event variants, each with an explicit `type` field set in its convenience constructor. No `@JsonTypeInfo` reflection, no shape-based discrimination on the client.
- **Postgres + Redis split with per-key atomicity.** Redis owns active state (24h TTL on `room:{id}` / `game:{id}`) behind a `StripedKeyLock` for single-instance serialization; Postgres owns the read-only archive of finished games, written transactionally inside the same `compute` block as the terminal-state mutation.
- **Disconnect/reconnect lifecycle with grace period.** A STOMP session drop schedules a one-shot 60-second abandon timer; a resubscribe with the matching `playerId` native header cancels it. `PlayerDisconnectedEvent` and `PlayerReconnectedEvent` drive the opponent's "reconnecting..." banner with an absolute `gracePeriodEndsAt` deadline.
- **Production deploy on AWS Free Tier with OIDC CI/CD.** EC2 + RDS + ECR provisioned by Terraform; Caddy terminates TLS via Let's Encrypt at `chess-backend.duckdns.org`; GitHub Actions authenticates to AWS via federated OIDC (no static keys anywhere) and gates each release on a `/api/health` smoke test.
- **Leader/implementer/reviewer agent harness with persisted state.** Three role files in `.claude/agents/` separate planning, execution, and verification. State outlives chat: scope in `feature_list.json`, active session in `progress/current.md`, audit trail in `progress/history.md`, learning notes in `notes/`.
- **200 tests with Testcontainers — no H2, no in-memory fakes.** Integration tests boot a real Postgres and Redis via Testcontainers and exercise the system through the REST + STOMP surfaces. Unit tests cover the domain layer (chess rules, edge cases) where a context boot would be wasted overhead.

## Architecture

```mermaid
flowchart TB
    Frontend["Frontend (React/Vite SPA)"]

    subgraph Backend["Spring Boot application (single process)"]
        direction TB
        Web["web/ (REST controllers, DTOs)"]
        WS["websocket/ (STOMP controllers, session trackers)"]
        Service["service/ (use cases, RoomService, GameService, GracePeriodManager)"]
        Cache["cache/ (RedisRoomStore, RedisGameStore)"]
        Persistence["persistence/ (JPA entities, repositories, Flyway)"]
        Domain["domain/ (records, enums, ChessRules wrapper)"]
    end

    Redis[("Redis (active rooms + games, 24h TTL)")]
    Postgres[("PostgreSQL (archived games, moves)")]
    Chesslib["chesslib (move validation, FEN, status)"]

    Frontend -->|"REST /api/**"| Web
    Frontend -->|"STOMP /ws"| WS

    Web --> Service
    WS --> Service

    Service --> Cache
    Service --> Persistence
    Service --> Domain

    Cache --> Redis
    Persistence --> Postgres
    Domain --> Chesslib
```

Dependency direction is strictly top-to-bottom; lower layers do not know about higher layers. See [`docs/architecture.md`](docs/architecture.md) for the full reasoning.

## End-to-end flow

```mermaid
sequenceDiagram
    actor PlayerA
    actor PlayerB
    participant Backend
    participant Redis
    participant Postgres

    PlayerA->>Backend: POST /api/rooms {displayName}
    Backend->>Redis: SET room:{roomId} (WAITING_FOR_PLAYER)
    Backend-->>PlayerA: 200 {roomId, playerId}
    PlayerA->>Backend: STOMP SUBSCRIBE /topic/rooms/{roomId}

    PlayerB->>Backend: POST /api/rooms/{roomId}/join {displayName}
    Backend->>Redis: compute room:{roomId} (ACTIVE) + SET game:{gameId}
    Backend-->>PlayerB: 200 {gameId, playerId, role: BLACK}
    Backend-->>PlayerA: /topic/rooms/{roomId} ROOM_JOINED {gameId, blackPlayer}

    PlayerA->>Backend: STOMP SUBSCRIBE /topic/games/{gameId} (playerId header)
    PlayerB->>Backend: STOMP SUBSCRIBE /topic/games/{gameId} (playerId header)

    PlayerA->>Backend: POST /api/games/{gameId}/moves (X-Player-Id: A) {from, to}
    Backend->>Redis: compute game:{gameId} (validate + apply + persist)
    Backend-->>PlayerA: 200 {fen, status, turn}
    Backend-->>PlayerA: /topic/games/{gameId} MOVE {fen, status, turn, ...}
    Backend-->>PlayerB: /topic/games/{gameId} MOVE {fen, status, turn, ...}

    Note over PlayerA,PlayerB: ...moves continue until terminal status...

    PlayerB->>Backend: POST /api/games/{gameId}/moves (X-Player-Id: B) {from, to}
    Backend->>Redis: compute game:{gameId} (CHECKMATE)
    Backend->>Postgres: INSERT games + moves (archive inside compute)
    Backend-->>PlayerB: 200 {fen, status: CHECKMATE}
    Backend-->>PlayerA: /topic/games/{gameId} MOVE {status: CHECKMATE}
    Backend-->>PlayerB: /topic/games/{gameId} MOVE {status: CHECKMATE}
```

The companion `chess-frontend` repo lives at <https://github.com/dariogguillen/chess-frontend>.

## Stack

- Java 21
- Spring Boot 3 (Spring MVC, Spring WebSocket, Spring Data JPA, Spring Data Redis)
- PostgreSQL + Flyway
- Redis
- WebSocket (STOMP, in-process `SimpleBroker`)
- `chesslib` for move validation, FEN handling, and status detection
- springdoc-openapi for the OpenAPI 3 spec + Swagger UI
- JUnit 5 + Testcontainers (real Postgres, real Redis — no H2, no in-memory fakes)
- Docker (multi-stage build) + Docker Compose
- Caddy (TLS termination via Let's Encrypt) + Terraform (AWS infra)
- Maven

## Running locally

The application ships three workflows. Pick the one that matches your task: fast inner-loop iteration, a production-like containerized run, or a hybrid where the IDE drives the app and Docker drives the infrastructure.

### With Testcontainers (primary dev loop)

`./mvnw spring-boot:test-run` boots the app with Testcontainers-managed Postgres and Redis. No Docker image build is required; Spring Boot's `spring-boot.run.test-only` hook applies the `@TestConfiguration` containers automatically. Use this for everyday development — it is the fastest path from change to running app.

```bash
./mvnw spring-boot:test-run
```

### With docker-compose (production-like stack)

`docker compose up --build` brings up Postgres, Redis, and the containerized app. The first build pulls base images and resolves Maven dependencies (~2–3 minutes); subsequent builds reuse the cached layers (~30s when only sources change). Use this to validate the actual deployment artifact end-to-end before opening a PR.

```bash
docker compose up --build
# stop and clean volumes when done
docker compose down -v
```

### Hybrid (Docker infra + IDE-attached app)

`docker compose up postgres redis -d` brings up only the dependencies, and `./mvnw spring-boot:run` runs the app on the host against them via the published ports (`localhost:5432`, `localhost:6379`). Use this for debugging when you want breakpoints attached to the app process.

```bash
docker compose up postgres redis -d
./mvnw spring-boot:run
```

Configuration follows the env-var-with-default pattern in `application.yml`: the same artifact runs locally, under Compose, and in production by overriding `SPRING_DATASOURCE_*` and `SPRING_DATA_REDIS_*` at start time.

## API

The application exposes a small REST surface: a liveness probe, the room lifecycle (create / join / read), and the game endpoints (read state, apply moves, list a player's archived games).

### API documentation

The HTTP API is documented via an auto-generated OpenAPI 3 spec.

- Interactive Swagger UI: <http://localhost:8080/swagger-ui.html>
- Machine-readable JSON spec: <http://localhost:8080/v3/api-docs>

To import into Insomnia or Postman, create a new collection from the `/v3/api-docs` URL.

### Quick curl examples

Room IDs are case-insensitive in URLs; responses always return the canonical uppercase form.

```bash
# Health probe.
curl http://localhost:8080/api/health

# Create a room (caller becomes WHITE).
curl -X POST http://localhost:8080/api/rooms \
  -H 'Content-Type: application/json' \
  -d '{"displayName":"Alice"}'

# Join an existing room (caller becomes BLACK; game is created).
curl -X POST http://localhost:8080/api/rooms/K7M3X9/join \
  -H 'Content-Type: application/json' \
  -d '{"displayName":"Bob"}'
```

### Games

```bash
# Read the current state of a game.
curl -X GET http://localhost:8080/api/games/<gameId>

# Apply a move (caller identified by X-Player-Id).
curl -X POST http://localhost:8080/api/games/<gameId>/moves \
  -H "Content-Type: application/json" \
  -H "X-Player-Id: <playerId>" \
  -d '{"from": "e2", "to": "e4"}'
```

### Authentication (optional)

Guest play stays open on every existing surface; an account unlocks "review my past games" (feature 19) and is the foundation for future per-user features. Three sign-in paths converge on the same `User` and the same JWT shape:

- `POST /api/auth/register` — `{ email, password, displayName }` → `201 { token, user }`.
- `POST /api/auth/login` — `{ email, password }` → `200 { token, user }`.
- Or sign in with Google — navigate the browser to `/oauth2/authorization/google`. The backend handles the OAuth dance, mints a JWT, and redirects to the frontend's `/auth/callback#token=<jwt>` so the frontend can read the token from the URL fragment. The Google sign-in path requires `GOOGLE_OAUTH_CLIENT_ID` / `GOOGLE_OAUTH_CLIENT_SECRET` / `AUTH_OAUTH_FRONTEND_REDIRECT_BASE` env vars at boot; see [`docs/architecture.md`](docs/architecture.md) → "Authentication → Google OAuth 2.0 sign-in" for the operator setup.

The returned `token` is a stateless HS256 JWT (7-day lifetime). Send it back as `Authorization: Bearer <token>` on subsequent requests to authenticated endpoints (today only `GET /api/me`). Full request/response shapes live in the Swagger UI linked above.

### WebSocket (STOMP)

Live game updates are pushed over STOMP-over-WebSocket. After every successful `POST /api/games/{id}/moves`, the server broadcasts a `MoveEvent` to subscribers of the game's topic.

- Endpoint: `ws://localhost:8080/ws`
- Subscribe to `/topic/games/{gameId}` to receive `GameStateEvent` variants (`MOVE`, `GAME_ABANDONED`, `PLAYER_DISCONNECTED`, `PLAYER_RECONNECTED`) — each carries an explicit `type` discriminator field.
- Subscribe to `/topic/rooms/{roomId}` to receive `RoomEvent` variants (today: `ROOM_JOINED`) — same discriminator pattern.
- Subscribe to `/topic/games/{gameId}/viewers` to receive a `ViewerCountEvent` on every spectator join/leave. Players self-exclude from the count by sending a `playerId:<uuid>` native STOMP header on their `SUBSCRIBE` to `/topic/games/{gameId}`.

See [`docs/architecture.md`](docs/architecture.md) → "STOMP API contract" for the full contract (payload shapes, allowed origins, failure mode, viewer count broadcasts, the `playerId` header convention, and the `GameStateEvent` family).

Smoke-test from the terminal with [`wscat`](https://github.com/websockets/wscat) (any STOMP-aware client works the same way):

```bash
# Connect to the WS endpoint.
wscat -c ws://localhost:8080/ws

# Once connected, paste the STOMP frames (each ends with a NUL byte,
# typed as ^@ in most terminals):
# CONNECT
# accept-version:1.2
# host:localhost
#
# ^@
#
# SUBSCRIBE
# id:sub-0
# destination:/topic/games/<gameId>
#
# ^@
```

## Deployment

The backend is deployed to AWS Free Tier (EC2 + RDS + ECR) with Caddy terminating HTTPS via Let's Encrypt at <https://chess-backend.duckdns.org>. Infrastructure lives in [`infra/`](infra/) (Terraform). The full step-by-step deploy procedure — `terraform apply`, Duck DNS, image transfer, smoke test, troubleshooting — is in [`docs/deploy-runbook.md`](docs/deploy-runbook.md).

Pushes to `main` deploy automatically via [`.github/workflows/deploy.yml`](.github/workflows/deploy.yml): the workflow authenticates to AWS via OIDC (no static keys), builds and pushes the Docker image to ECR, SSHes into the EC2 to pull and restart the container, and smoke-tests `/api/health` before reporting green. PRs targeting `main` are gated by [`.github/workflows/ci.yml`](.github/workflows/ci.yml), which runs `./init.sh` on the candidate branch. Both workflows share the JDK-setup + verify steps via the composite action at `.github/actions/build/action.yml`. The manual procedure in the runbook remains the fallback and the single source of truth for what each automated stage is doing.

## Engineering process

This repo is coordinated by an agent harness with three explicit roles and on-disk state that survives across chat sessions. The shape is recruiter-grade differentiation: the same discipline a senior engineer would expect from a code reviewer is encoded in files, not in memory.

- [`CLAUDE.md`](CLAUDE.md) — entry-point orchestration rules. Every session starts here; this file pins the agent to the `leader` role and lists the four files to read on session start.
- [`AGENTS.md`](AGENTS.md) — project map for any agent walking in cold. Points at where information lives (architecture, conventions, scope, verification) so the agent looks things up on demand rather than carrying everything in context.
- [`.claude/agents/leader.md`](.claude/agents/leader.md) — decomposes scope into features and coordinates the implementer/reviewer cycle. Never writes code. Owns `feature_list.json` status transitions.
- [`.claude/agents/implementer.md`](.claude/agents/implementer.md) — executes the locked plan exactly. Never picks scope, never self-reviews. Ships code, tests, and the feature note in `notes/` as a single deliverable.
- [`.claude/agents/reviewer.md`](.claude/agents/reviewer.md) — walks [`CHECKPOINTS.md`](CHECKPOINTS.md) and approves or rejects with specific, file-pinned issues. Rejects on missing or empty feature notes.
- [`feature_list.json`](feature_list.json) — single source of truth for scope, ordered by priority. Only the leader flips `status` (`pending` → `in_progress` → `done`).
- [`progress/current.md`](progress/current.md) and [`progress/history.md`](progress/history.md) — persisted session state. `current.md` is the active feature's plan; `history.md` is the append-only audit trail of every feature closed.
- [`notes/`](notes/) — feature-by-feature learning trail, one note per feature at `notes/NN-<feature-id>.md`. Required deliverable; the reviewer rejects features that ship without one.

Verification is performed exclusively by [`./init.sh`](init.sh) — a single script that compiles, lints, and runs the full test suite. Only a green run counts as evidence that a feature is complete.

## Repository structure

```
chess-backend-java/
├── .claude/agents/                     # leader / implementer / reviewer role definitions
├── .github/
│   ├── actions/build/                  # composite action: JDK setup + ./init.sh
│   └── workflows/                      # ci.yml (PR gate) + deploy.yml (push to main)
├── docs/                               # architecture, conventions, deploy runbook, verification
├── infra/                              # Terraform sources for the AWS Free Tier stack
├── notes/                              # feature notes (one per feature, NN-<feature-id>.md)
├── progress/                           # current.md (active session) + history.md (audit trail)
├── src/
│   ├── main/java/io/github/dariogguillen/chess/
│   │   ├── config/                     # @Configuration classes
│   │   ├── domain/                     # records, enums, ChessRules wrapper
│   │   ├── service/                    # use cases (RoomService, GameService, ...)
│   │   ├── web/                        # REST controllers + request/response DTOs
│   │   ├── websocket/                  # STOMP controllers + session trackers + event DTOs
│   │   ├── persistence/                # JPA entities + Spring Data repositories
│   │   ├── cache/                      # Spring Data Redis stores
│   │   └── exception/                  # exception hierarchy + global handler
│   ├── main/resources/                 # application.yml, Flyway migrations
│   └── test/java/                      # mirrors main layout; *IT integration, *Test unit
├── AGENTS.md                           # project map
├── CHECKPOINTS.md                      # "done" checklist used by the reviewer
├── CLAUDE.md                           # entry-point orchestration rules
├── docker-compose.yml                  # local Postgres + Redis + app
├── docker-compose.prod.yml             # production compose (Redis + app, no Postgres; RDS in prod)
├── Dockerfile                          # multi-stage build (JDK + Maven builder, JRE runtime)
├── feature_list.json                   # scope, ordered by priority
├── init.sh                             # compile + lint + test; the verification gate
└── pom.xml
```

## Out of scope (deliberate)

These items were considered and explicitly deferred. They are documented here so reviewers see the boundaries as decisions, not gaps.

- **Authentication.** Players are identified by an opaque UUID set at room creation. A real product would have OAuth, OIDC, or session cookies; the `X-Player-Id` header and the STOMP `playerId` native header are deliberate placeholders for that future layer.
- **Ratings (ELO).** The data model leaves room for it (the archive stores per-player UUIDs and display names); no implementation today.
- **Tournament structure.** One game at a time per room.
- **Move clocks / time controls.** Possible future feature; the `GameStatus` enum and the move-archive schema leave room without a migration.

## License

GPL-3.0
