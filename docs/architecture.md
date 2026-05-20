# Architecture

This document describes what "good work" looks like in this project. It
covers the architectural decisions, the reasoning behind them, and the
boundaries between components. If a decision is not documented here and
not obvious from the code, prefer **the simplest standard Spring Boot
approach** and surface the question with the user.

---

## High-level shape

A single Spring Boot servlet application. Not microservices, not modular
monolith. One process, one deployable, one `pom.xml`.

The application exposes:

- **REST endpoints** under `/api/...` for state-changing actions
  (create room, make a move, query state).
- **STOMP over WebSocket** at `/ws` for real-time broadcasts to clients
  in a room.
- A **health endpoint** at `/api/health` for liveness and version info.

The application depends on two external systems:

- **PostgreSQL** for durable state (completed games, players).
- **Redis** for ephemeral state (active rooms, active games, player
  connection state).

Both are managed via Docker Compose locally and via Testcontainers in
integration tests.

## Layered architecture

The dependency direction is strictly top to bottom. Lower layers do not
know about higher layers.

```
web / websocket
       │
       ▼
    service
       │
       ▼
  persistence / cache / domain (chesslib)
```

- **`web/`** holds REST controllers. Controllers translate HTTP to
  service calls and back. They never call repositories directly.
- **`websocket/`** holds STOMP controllers. Same role for WebSocket
  traffic.
- **`service/`** holds application services. Services orchestrate
  domain operations, validation, persistence, and broadcasting. This
  is where transactional boundaries live.
- **`domain/`** holds entities, value objects, and pure domain logic
  (e.g., wrappers over chesslib). No Spring annotations here if
  possible.
- **`persistence/`** holds JPA repositories and Postgres-backed code.
- **`cache/`** holds Spring Data Redis repositories and caching
  utilities.
- **`exception/`** holds the exception hierarchy and the global
  `@RestControllerAdvice`.
- **`config/`** holds `@Configuration` classes (WebSocket, security
  later, etc.).

`service/` may introduce its own value types when the domain shape does
not match the service's computational needs. For example, `ChessRules`
operates on a service-level `GameState` record (`startingFen + history +
cached current views`) rather than the domain `Game`, because chess-rule
decisions need position history but not player identity. Such
service-level types live alongside the service that consumes them and
are mapped to and from domain types at the service boundary.

`service/` also holds the **storage seams** for active state: small
interfaces like `RoomStore` and `GameStore`, with `cache/InMemoryRoomStore` /
`cache/InMemoryGameStore` as the only registered implementations today. The
seam is the swap point for feature 7 (Redis-backed active state) — the
in-memory beans are replaced by `RedisRoomStore` / `RedisGameStore`
without touching `RoomService` or any consumer above the service layer.
Putting the interfaces in `service/` and their implementations in `cache/`
keeps the port next to its sole consumer while the adapters live alongside
the future Redis-backed siblings.

### API contract

The REST surface is documented via an OpenAPI 3 spec generated at
runtime by **springdoc-openapi**. The spec is served at
`/v3/api-docs` (JSON) and `/swagger-ui.html` (interactive UI). The
source of truth lives in the controllers themselves: `@Tag`,
`@Operation`, and `@ApiResponse` annotations on each `@RestController`,
plus selective `@Schema` annotations on the record DTOs. A
top-level `@Bean OpenAPI` in `config/` contributes the title,
description, and build version. The WebSocket / STOMP surface is
intentionally out of springdoc's scope; it is documented in the
"STOMP API contract" section below.

## STOMP API contract

REST is the entry point for **mutations** (create room, join room,
apply a move). STOMP is the side channel for **read-only push** —
after a move is accepted on the REST side, the server broadcasts a
`MoveEvent` to every subscriber of that game's topic. This section
is the source of truth for the STOMP surface; the `chess-frontend`
repo mirrors it in its own `docs/architecture.md` when it reaches
its feature 5 (`stomp-live-updates`).

### Endpoint and broker

- **WebSocket endpoint:** `/ws`. Clients perform a STOMP `CONNECT`
  over native WebSocket. SockJS fallback is **not** enabled —
  modern browsers plus the targeted `@stomp/stompjs` frontend
  client handle native WebSocket fine, and SockJS would add
  surface area we do not need.
- **Broker:** Spring's in-process `SimpleBroker`, registered on
  the `/topic` prefix. Subscriptions and fan-out happen inside
  the application process — sufficient for a single-instance
  deployment. Scaling out to multiple instances would require an
  external broker (RabbitMQ, ActiveMQ, or equivalent) so that a
  broadcast on instance A reaches subscribers connected to
  instance B. That is a documented constraint to revisit, not a
  current concern.
- **Application destination prefix:** `/app`, registered for
  future-proofing. This feature does not introduce any client-to-
  server STOMP messages — `MoveEvent` is the only traffic, and it
  flows server-to-client. The prefix is in place so a future
  `@MessageMapping` endpoint can land without a config change.

### Allowed origins (CORS for the WebSocket handshake)

The `/ws` endpoint's allowed origin patterns:

- `https://dariogguillen.github.io` (production frontend, GitHub
  Pages).
- `http://localhost:*` (development frontend on any localhost
  port).

We use `setAllowedOriginPatterns` (not `setAllowedOrigins`)
because Spring disallows `setAllowedOrigins("*")` combined with
credentials; an explicit pattern list is the canonical workaround.
The list mirrors the existing CORS strategy on the REST side.

### Subscriptions

Clients subscribe to **`/topic/games/{gameId}`** — one logical
channel per game. Every successful move applied via
`POST /api/games/{id}/moves` produces exactly one `MoveEvent` on
this channel. There is no replay — subscribers only see events
that occur while they are subscribed. A client that joins late
catches up by calling `GET /api/games/{id}` once and then relying
on STOMP for the live tail.

### `MoveEvent` shape

Broadcast payload, serialized as JSON by Spring's default Jackson
converter:

```json
{
  "gameId": "550e8400-e29b-41d4-a716-446655440000",
  "movedBy": "8f14e45f-ceea-467a-9575-d4b9b3e8b3a3",
  "side": "WHITE",
  "from": "e2",
  "to": "e4",
  "promotion": null,
  "fen": "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1",
  "status": "ONGOING",
  "turn": "BLACK",
  "moveNumber": 1,
  "playedAt": "2026-05-19T10:23:11.123Z"
}
```

Field documentation:

- **`gameId`** — the id of the game the move was applied to;
  matches the `{gameId}` segment in the topic.
- **`movedBy`** — the id of the player that submitted the move
  (the value of `X-Player-Id` on the REST request). Lets a client
  filter out its own moves; the REST response already carried the
  new state, so re-processing the STOMP event would be redundant.
- **`side`** — `WHITE` or `BLACK`, the side that played this
  move. Redundant with `movedBy` server-side (we know which side
  each player is) but a convenience for the client.
- **`from`** — the origin square in lowercase algebraic
  notation, e.g. `"e2"`. Same alphabet as the REST `MoveRequest`.
- **`to`** — the destination square, same alphabet.
- **`promotion`** — `"KNIGHT"`, `"BISHOP"`, `"ROOK"`, `"QUEEN"`,
  or `null` for non-promotion moves. Mirrors the REST request's
  optional promotion field.
- **`fen`** — the resulting position after the move was applied,
  in Forsyth-Edwards Notation.
- **`status`** — the resulting `GameStatus`: `ONGOING`, `CHECK`,
  `CHECKMATE`, `STALEMATE`, `DRAW`, or `ABANDONED`.
- **`turn`** — the side whose turn it is now (the inverse of
  `side`). Convenience for the client.
- **`moveNumber`** — the 1-based count of half-moves played in
  the game after this move. Useful for ordering and for
  detecting missed events (a gap in the sequence means the
  subscriber missed something and should re-sync via REST).
- **`playedAt`** — ISO-8601 instant in UTC, sourced from the
  service's injected `Clock` so tests can pin it deterministically.

### No STOMP-level authentication

STOMP `CONNECT` does not carry credentials. Anyone who knows a
`gameId` can subscribe to its topic and observe the live move
stream. This is a **deliberate** choice for the portfolio scope:

- It mirrors the REST `GET /api/games/{id}` design, which is also
  unauthenticated.
- It is the foundation that **feature 6.5 (spectator mode)**
  builds on — a spectator is just any party that subscribed to the
  topic without being one of the two players.
- A real product would lock both surfaces behind the same auth
  layer (OAuth, session cookies, JWT — pick one). We have not
  introduced that layer; when we do, both surfaces gain it
  together.

The mutation surface (REST `POST /api/games/{id}/moves`) is
already gated on `X-Player-Id` matching the side to move — a
subscriber who is not one of the two players cannot inject moves
into the game, only observe them.

### Failure mode

Broadcasts are **fire-and-forget**. After `GameStore.compute`
returns successfully and the mutation is persisted, the service
attempts `SimpMessagingTemplate.convertAndSend("/topic/games/" +
gameId, event)`. If the call throws a `RuntimeException` (broker
misconfigured, serialization fails, etc.), the service logs at
`WARN` with the `gameId` and the exception message, and does not
rethrow. Specifically:

- The REST POST still returns 200 with the updated state — the
  client that submitted the move has authoritative confirmation
  via REST, and broadcast loss does not break the originator.
- Subscribers may miss this update. Recovery is a client concern
  handled by the standard pattern: on STOMP disconnect or
  inconsistency, re-fetch state via
  `GET /api/games/{id}` and resume on the topic.

The broadcast happens **outside** the `compute` lambda. A failing
broadcast inside the lambda would propagate out of `compute` and
look like a failed mutation, which it is not — the state is
already committed. This is the same separation-of-concerns
principle that keeps controllers free of try/catch.

### Ordering and concurrency

`ConcurrentHashMap.compute` serializes concurrent moves on the
same `gameId`, so the broadcast for move N completes (or fails)
before the broadcast for move N+1 begins. Subscribers see moves
in the order they were applied. Broadcasts for **different**
games run in parallel; `SimpMessagingTemplate.convertAndSend` is
thread-safe.

### Viewer count broadcasts

Feature 6.5 (spectator mode) layers a second broadcast onto the
same `/ws` endpoint. Every time the set of sessions subscribed to
`/topic/games/{gameId}` changes — a new subscribe, an explicit
unsubscribe, or a session disconnect — the server publishes a
`ViewerCountEvent` to **`/topic/games/{gameId}/viewers`**:

```json
{
  "gameId": "0d52a8a0-bea0-4b21-bbe3-3df7f8e83bfb",
  "count": 3
}
```

- **`gameId`** — the game the count is for; matches the
  `{gameId}` segment in the topic. Clients may subscribe to
  several `/viewers` topics in parallel and use this field to
  demultiplex.
- **`count`** — the current number of subscribers to
  `/topic/games/{gameId}` that are **not** players of the game
  (see the `playerId` convention below).

The viewer-count topic exists separately from the game topic so
that count updates are decoupled from move cadence: a game with
no moves but lots of joiners and leavers still produces a stream
of count changes. The two topics are independent — clients can
subscribe to one, the other, or both.

The viewer-count broadcast is fire-and-forget on the same
failure-mode policy as `MoveEvent`: any `RuntimeException` from
the broker is logged at `WARN` and not rethrown.

### `playerId` header convention

Clients that are one of the two players of a game declare it by
sending their player id as a native STOMP header on the
`SUBSCRIBE` frame to `/topic/games/{gameId}`:

```
SUBSCRIBE
id:sub-0
destination:/topic/games/<gameId>
playerId:<playerId-uuid>

^@
```

The server compares the header value against `white.id()` and
`black.id()` of the game. A match means "this subscriber is a
player, not a spectator" and they are excluded from the viewer
count for that game. No header (or no match) means "this
subscriber is a spectator" and they are counted.

**Trust model:** the server takes the header at face value.
There is no authentication today, so the claim cannot be
verified — a malicious client could omit the header to inflate
the count, or forge another player's id to exclude itself. This
is a **deliberate portfolio-level trade-off**, consistent with
the rest of the no-auth design described above. A future auth
feature would replace "trust" with "verify" without changing the
header name or its semantics; only the validation step on the
server changes.

### Cross-repo coordination

The `chess-frontend` repo mirrors this section in its own
`docs/architecture.md` when it reaches feature 5
(`stomp-live-updates`). Until then, the contract above is the
single source of truth. Changes here must be reflected in the
frontend doc the next time the two are touched. With feature 6.5
in place, the frontend's feature 5 should additionally:

- Subscribe to `/topic/games/{gameId}/viewers` to render the
  live spectator count.
- Optionally send the `playerId` native header on the
  `SUBSCRIBE` to `/topic/games/{gameId}` when the current user
  is one of the two players of the game.

## Source of truth

The **server** is the source of truth for game state. The client can
display a board, but the server validates every move. This is the key
behavioral difference from the Node version, which trusted the client.

A move is only accepted if:

1. The game exists and is `ONGOING`.
2. The requester is one of the two players in the game.
3. It is that player's turn.
4. The move is legal according to chesslib given the current position.

If any of these fail, the server rejects the move with `422 Unprocessable
Entity` and the client must reconcile its state with the server's.

## State strategy

Two stores with clear responsibilities:

**Redis — active state, ephemeral.**

- Active rooms (waiting for second player or with an in-progress game).
- Active game state (current FEN, turn, status, players' connection
  state).
- TTL on every key (e.g., 24 hours). Activity refreshes the TTL.
- If Redis is wiped, in-flight games are lost. That is acceptable for
  a portfolio chess app.

**Postgres — durable history.**

- Completed games (result, full move history, players, timestamps).
- Player records (just an ID and a display name for now; no auth).
- Schema managed by Flyway under `src/main/resources/db/migration/`.

A game lives in Redis while it is active. When it completes (checkmate,
draw, resignation, abandonment), it is written to Postgres and removed
from Redis.

## Communication patterns

**REST for actions.** Anything that changes state goes through a REST
endpoint. This makes the API explicit, easy to test with `curl` or
HTTP integration tests, and easy to document.

**STOMP for broadcasts.** When a move is accepted, the server publishes
the new state on `/topic/games/{id}`. Subscribers (the opponent, and
optionally spectators) receive it.

The client therefore has two parallel channels:

- It **sends** moves over REST.
- It **receives** state updates over STOMP.

This avoids the complexity of bidirectional WebSocket message protocols
and keeps actions auditable.

## Reconnection

Players have a connection state stored in Redis: `CONNECTED`,
`DISCONNECTED`, `RECONNECTING`. When a STOMP session disconnects, the
player enters a grace period (target: 60 seconds). If they reconnect
within the grace period, they resume the game. If not, the game is
marked `ABANDONED` with the opponent as the winner.

Reconnection works because state is on the server, not in the browser.
A client that reloads the page can re-fetch the game state and
re-subscribe to the STOMP topic.

## Error handling

A small exception hierarchy lives in `exception/`. Examples:

- `RoomNotFoundException` → `404`.
- `RoomFullException` → `409`.
- `IllegalMoveException` → `422`.
- `NotYourTurnException` → `422`.

A single `@RestControllerAdvice` maps each exception to a structured JSON
body: `{ "error": "ROOM_NOT_FOUND", "message": "..." }`. Controllers do
not catch their own exceptions.

## Testing strategy

Two flavors of test:

- **Unit tests** for domain logic (chess rules wrapper, simple service
  logic that has no Spring dependencies). Plain JUnit 5. Fast. Live
  alongside production code in `src/test/java/...`.
- **Integration tests** with the `IT` suffix. Boot a Spring context,
  use Testcontainers for real Postgres and Redis, exercise the system
  end to end (REST + STOMP). These are slower but they are the ones
  that prove the system works.

No H2. No in-memory Redis fakes. Real services in tests, always.

## Observability

The project ships with the basics. We are not building a full
observability stack, but a portfolio backend should not be a black box.

- **Structured logs** (JSON) via Logback. Each request gets a
  correlation ID propagated through the call.
- **Spring Boot Actuator** for `/api/health` and `/api/info`.
- Metrics will be added if we have time. Micrometer + Prometheus is the
  obvious path.

## What is intentionally out of scope

- **Authentication.** Players are identified by an opaque ID set at
  room creation. A real product would have OAuth or similar.
- **Ratings (ELO).** The data model leaves room for it; we do not
  implement it.
- **Spectators.** Topic subscription would allow them, but we do not
  build the UX for it.
- **Tournament structure.** One game at a time per room.
- **Move clocks / time controls.** Possible future feature.

Document these out-of-scope items in the README so reviewers know they
were considered and deferred, not forgotten.
