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
