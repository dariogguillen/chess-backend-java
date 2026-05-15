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

| Method | Path          | Description                                                                 |
| ------ | ------------- | --------------------------------------------------------------------------- |
| `GET`  | `/api/health` | Liveness probe. Returns `200 OK` with `{ status, version, timestamp }`.     |

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

