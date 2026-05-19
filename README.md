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

> Coming soon. The project bootstraps with Maven and runs against Postgres and
> Redis via Docker Compose. Detailed instructions will be added once the first
> end-to-end flow is wired up.

```bash
./mvnw clean compile
```

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

