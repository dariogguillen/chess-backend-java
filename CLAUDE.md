# CLAUDE.md — chess-backend-java

This file is read by Claude Code at the start of every session. It defines what
this project is, how the code is organized, and the conventions to follow.

---

## Project context

This is the Java/Spring Boot backend for an online multiplayer chess game. It
powers:

- Room creation and joining via shareable link
- Real-time move synchronization between two players (WebSocket)
- Chess move validation (rules, check, mate, stalemate)
- Game state persistence

A previous version of this backend exists in Node + TypeScript at
`https://github.com/dariogguillen/chess-game` (folder `backend/`). When porting
features, **read that code as reference for what the system does**, but DO NOT
translate Node patterns directly to Java. Use idiomatic Java/Spring design.

## Stack

- **Java 21** (LTS)
- **Spring Boot 3.x**
- **Maven** (not Gradle)
- **PostgreSQL** via Spring Data JPA
- **Flyway** for DB migrations
- **Redis** via Spring Data Redis (active game state + room cache)
- **Spring WebSocket + STOMP** for real-time
- **JUnit 5 + Testcontainers** for testing (real Postgres + Redis in tests)
- **chesslib** (`com.github.bhlangonijr:chesslib`) for move validation
- **Docker Compose** for local development

## Package structure

Base package: `io.github.dariogguillen.chess`

```
io.github.dariogguillen.chess
├── config/          # Spring configuration classes
├── domain/          # Entities, value objects, domain logic
├── service/         # Application services / use cases
├── web/             # REST controllers + request/response DTOs
├── websocket/       # WebSocket controllers + message DTOs
├── persistence/     # JPA repositories
├── cache/           # Redis repositories and caching utilities
└── exception/       # Custom exceptions + global handler
```

## Code conventions

- **DTOs:** always separate from domain entities. Never expose JPA entities
  through controllers.
- **Records** for DTOs (request/response/message).
- **Constructor injection** for all dependencies — no `@Autowired` on fields.
- **`@Transactional`** on service methods that mutate state.
- **JavaDoc** on public methods of services explaining contract and side effects.
- **Tests** live in `src/test/java/...`, mirroring the main package structure.
- **Integration tests** use Testcontainers — real Postgres, real Redis. No
  in-memory or mock databases.

## What NOT to do

- Don't add new dependencies without justifying it in the commit message or PR.
- Don't modify Flyway migrations that have already been applied — add new ones.
- Don't bypass services — controllers must never call repositories directly.
- Don't implement chess rules from scratch — use the chesslib library.
- Don't introduce reactive (WebFlux). This project is servlet-based Spring MVC.
- Don't add Lombok unless we discuss it first. Prefer records and explicit
  constructors.
- Don't commit secrets, `.env` files, or anything in `application-local.yml`.

## How to verify

Before declaring any task done, run:

```bash
./verify.sh
```

This runs, in order: format check (spotless), compile, unit tests, integration
tests. All must pass. If any step fails, fix it before reporting back.

## Workflow for every non-trivial task

1. Read this CLAUDE.md (you are doing this now).
2. Read `PLAN.md` at the repo root for the current feature being worked on.
3. If `PLAN.md` does not match the task, or no plan exists, propose one and
   **wait for approval** before writing code.
4. Implement the plan. Make small, focused commits.
5. Run `./verify.sh`. Fix anything that fails.
6. Summarize what was done and any deviations from the plan.

## Reference repo

The previous Node/TypeScript backend (for understanding behavior, not for
copying patterns):

- Repo: `https://github.com/dariogguillen/chess-game`
- Folder: `backend/`
- Treat it as a spec, not as a source of code.

## Owner notes

This project is a portfolio piece for a Senior Backend role. Quality, clarity,
and good engineering practices matter more than feature count. If a shortcut
would compromise the demonstration of senior-level work, don't take it.
