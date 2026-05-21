# chess-backend-java

Backend service for an online multiplayer chess game. Two players connect
through a shareable room and play in real time. This is the Java rewrite of an
earlier Node/TypeScript backend ([chess-game](https://github.com/dariogguillen/chess-game)).

This repo is a portfolio project. It's a work in progress.

## Stack

- Java 21
- Spring Boot 3
- PostgreSQL + Flyway
- Redis
- WebSocket (STOMP)
- JUnit 5 + Testcontainers
- Docker Compose
- Maven

## Status

Currently in early development. The current scope and feature plan lives in
`feature_list.json`; the active session plan (if any) lives in
`progress/current.md`.

## Running locally

The application ships three workflows. Pick the one that matches your task:
fast inner-loop iteration, a production-like containerized run, or a hybrid
where the IDE drives the app and Docker drives the infrastructure.

### With Testcontainers (primary dev loop)

`./mvnw spring-boot:test-run` boots the app with Testcontainers-managed
Postgres and Redis. No Docker image build is required; Spring Boot's
`spring-boot.run.test-only` hook applies the
`@TestConfiguration` containers automatically. Use this for everyday
development — it is the fastest path from change to running app.

```bash
./mvnw spring-boot:test-run
```

### With docker-compose (production-like stack)

`docker compose up --build` brings up Postgres, Redis, and the
containerized app. The first build pulls base images and resolves Maven
dependencies (~2–3 minutes); subsequent builds reuse the cached layers
(~30s when only sources change). Use this to validate the actual
deployment artifact end-to-end before opening a PR.

```bash
docker compose up --build
# stop and clean volumes when done
docker compose down -v
```

### Hybrid (Docker infra + IDE-attached app)

`docker compose up postgres redis -d` brings up only the dependencies,
and `./mvnw spring-boot:run` runs the app on the host against them via
the published ports (`localhost:5432`, `localhost:6379`). Use this for
debugging when you want breakpoints attached to the app process.

```bash
docker compose up postgres redis -d
./mvnw spring-boot:run
```

Configuration follows the env-var-with-default pattern in
`application.yml`: the same artifact runs locally, under Compose, and
in production by overriding `SPRING_DATASOURCE_*` and
`SPRING_DATA_REDIS_*` at start time.

## API

The application exposes a small REST surface: a liveness probe, the room lifecycle
(create / join), and the game endpoints (read state, apply moves). The WebSocket (STOMP) surface
for real-time game updates lands with feature 6 and is documented separately in this README when
it does.

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

### WebSocket (STOMP)

Live game updates are pushed over STOMP-over-WebSocket. After every
successful `POST /api/games/{id}/moves`, the server broadcasts a
`MoveEvent` to subscribers of the game's topic.

- Endpoint: `ws://localhost:8080/ws`
- Subscribe to `/topic/games/{gameId}` to receive a `MoveEvent` per move.
- Subscribe to `/topic/games/{gameId}/viewers` to receive a `ViewerCountEvent`
  on every spectator join/leave (feature 6.5). Players self-exclude from the
  count by sending a `playerId:<uuid>` native STOMP header on their
  `SUBSCRIBE` to `/topic/games/{gameId}`.

See `docs/architecture.md` → "STOMP API contract" for the full
contract (payload shape, allowed origins, failure mode, viewer
count broadcasts, and the `playerId` header convention).

Smoke-test from the terminal with [`wscat`](https://github.com/websockets/wscat)
(any STOMP-aware client works the same way):

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

The backend is deployed to AWS Free Tier (EC2 + RDS + ECR) with Caddy
terminating HTTPS via Let's Encrypt at <https://chess-backend.duckdns.org>.
Infrastructure lives in `infra/` (Terraform). The full step-by-step
deploy procedure — `terraform apply`, Duck DNS, image transfer, smoke
test, troubleshooting — is in [`docs/deploy-runbook.md`](docs/deploy-runbook.md).
The deploy is manual for now; feature 7.7 will automate it via GitHub
Actions + OIDC + ECR.

## Repository structure

```
src/main/java/io/github/dariogguillen/chess/
├── config/          # Spring configuration
├── domain/          # Entities, value objects, domain logic
├── service/         # Application services
├── web/             # REST controllers + DTOs
├── websocket/       # WebSocket controllers + message DTOs
├── persistence/     # JPA repositories
├── cache/           # Redis repositories
└── exception/       # Custom exceptions + global handler
```

## License

GPL-3.0

