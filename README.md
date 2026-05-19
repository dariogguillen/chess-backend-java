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

| Method | Path                       | Description                                                                                                                                                |
| ------ | -------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `GET`  | `/api/health`              | Liveness probe. Returns `200 OK` with `{ status, version, timestamp }`.                                                                                    |
| `POST` | `/api/rooms`               | Creates a room with the caller as the single player (becomes `WHITE`). Returns `201 Created` with `{ roomId, playerId, role, gameId }` (`gameId` is null). |
| `POST` | `/api/rooms/{id}/join`     | Joins an existing room as the second player (becomes `BLACK`) and starts the game. Returns `200 OK` with `{ roomId, playerId, role, gameId }`.             |

### Room endpoint examples

Create a room:

```bash
curl -X POST http://localhost:8080/api/rooms \
  -H 'Content-Type: application/json' \
  -d '{"displayName":"Alice"}'
# 201 Created
# { "roomId": "K7M3X9", "playerId": "8b3c...-...", "role": "WHITE", "gameId": null }
```

Join an existing room:

Room IDs are case-insensitive in URLs; responses always return the canonical uppercase form.

```bash
curl -X POST http://localhost:8080/api/rooms/K7M3X9/join \
  -H 'Content-Type: application/json' \
  -d '{"displayName":"Bob"}'
# 200 OK
# { "roomId": "K7M3X9", "playerId": "1f04...-...", "role": "BLACK", "gameId": "0d52...-..." }
```

Errors come back as `{ error, message, timestamp }`: `ROOM_NOT_FOUND` (404), `ROOM_FULL` (409),
`VALIDATION_FAILED` / `MALFORMED_REQUEST` (400).

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

