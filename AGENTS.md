# AGENTS.md — Map of this repository

This file is the entry point for any AI agent working on this project. It is a
map, not a reference document. It points to where information lives; you look
things up on demand instead of carrying everything in context.

---

## What this project is

`chess-backend-java` is a Java + Spring Boot backend for an online multiplayer
chess game. Two players connect through a shareable room and play in real
time. This is a rewrite of an earlier Node + TypeScript backend at
`https://github.com/dariogguillen/chess-game` (folder `backend/`).

The Node version is a **behavioral specification**, not a code reference. The
Java version intentionally improves on it by:

- Moving move validation to the **server** (the Node version trusted the
  client).
- Adding **persistence** (the Node version held state in an in-memory `Map`).
- Adding **disconnect / reconnect** handling with grace periods.
- Adding **proper observability** (structured logs, health endpoint, metrics).

This is a portfolio project. Engineering quality matters more than feature
count.

## How to work in this repo

The workflow for every non-trivial change is:

1. Read `AGENTS.md` (you are here).
2. Read your role definition under `.claude/agents/`.
3. Open `feature_list.json`. Find the pending feature with the lowest
   `priority` value. If more than one feature has `status: "in_progress"`,
   stop and report it to the user — that is a violation of the
   one-feature-at-a-time invariant enforced by `./init.sh`.
4. Mark the selected feature as `in_progress`.
5. Write a short plan in `progress/current.md`. The plan covers:
   - Feature ID and title.
   - The files that will be created or modified.
   - The verification approach (which tests prove it works).
6. Wait for user approval of the plan unless instructed otherwise.
7. Implement (or delegate to `implementer`). Make small commits.
8. Run `./init.sh`. All steps must pass.
9. Have `reviewer` validate, if defined.
10. Mark the feature as `done` in `feature_list.json`.
11. Append a one-paragraph entry to `progress/history.md`.
12. Replace `progress/current.md` with a "session closed" note.

## Where things live

| Topic | Location |
| --- | --- |
| Project orchestration role | `CLAUDE.md` |
| Project map (this file) | `AGENTS.md` |
| Feature scope and state | `feature_list.json` |
| Verification script | `./init.sh` |
| Active session plan | `progress/current.md` |
| Session log (append-only) | `progress/history.md` |
| Architectural guidelines | `docs/architecture.md` |
| Code conventions | `docs/conventions.md` |
| Verification details | `docs/verification.md` |
| "Done" checklist | `CHECKPOINTS.md` |
| Sub-agent definitions | `.claude/agents/` |
| Automated hooks | `.claude/settings.json` |

If you need a rule, check the table first. Do not invent rules from memory.

---

## Quick summary (canonical references in `docs/`)

This section is a fast-access summary. The authoritative versions are in
`docs/architecture.md` and `docs/conventions.md`.

### Stack

- **Java 21**, **Spring Boot 3.x**, **Maven**.
- **PostgreSQL** via Spring Data JPA, with **Flyway** for migrations.
- **Redis** via Spring Data Redis (synchronous client — not reactive).
- **Spring WebSocket + STOMP** for real-time messaging.
- **JUnit 5** + **Testcontainers** for tests. Real Postgres, real Redis. No
  H2, no embedded fakes for anything that hits Postgres or Redis in
  production.
- **`com.github.bhlangonijr:chesslib`** for move validation, check, mate,
  stalemate, and FEN handling. Do not reimplement chess rules.
- **Docker Compose** for local infra.

### Base package

`io.github.dariogguillen.chess`

### Package layout

```
io.github.dariogguillen.chess
├── config/          # Spring configuration classes
├── domain/          # Entities, value objects, domain logic
├── service/         # Application services / use cases
├── web/             # REST controllers + request/response DTOs
├── websocket/       # WebSocket controllers + STOMP message DTOs
├── persistence/     # JPA repositories + Postgres-backed code
├── cache/           # Spring Data Redis repositories + caches
└── exception/       # Custom exceptions + global handler
```

### Code conventions (short form)

- **DTOs are records**, always separate from JPA entities. Never expose
  entities through controllers.
- **Constructor injection only.** No `@Autowired` on fields. No setter
  injection.
- **`@Transactional`** on service methods that mutate state. Read-only
  paths do not require it.
- **Public service methods** must have JavaDoc explaining contract and
  side effects.
- **Tests** mirror the main package layout under `src/test/java/...`.
  Integration tests end with `IT` (e.g., `RoomServiceIT`).
- **No Lombok.** Prefer records and explicit constructors.
- **No WebFlux.** This is servlet-based Spring MVC.
- **Exceptions** from services are mapped to HTTP by a global handler in
  `exception/`. Controllers do not handle exceptions directly.

### When in doubt

- Prefer the simpler design over the clever one.
- Prefer the explicit name over the short one.
- Prefer adding a test over adding a comment.
- Prefer the standard Spring Boot way over a custom abstraction.

---

## Reference: the Node backend

The Node backend at `https://github.com/dariogguillen/chess-game`
(`backend/src/index.ts`) is a behavioral specification. Treat its socket
events as the source of truth for *what* the system does, not *how* it
should be coded.

Notable behaviors:

- `createRoom` — generate a UUID, register the creator as player 1.
- `joinRoom` — validate that the room exists and has exactly 1 player,
  then add the joiner as player 2.
- `move` — broadcast a move to the other socket in the same room. The
  Node version does **not** validate the move on the server; the Java
  version does.
- `disconnect` — if the disconnected player was the only one in the room,
  delete the room.
- `closeRoom` — explicit close that removes remaining sockets from the
  room and deletes it.

The Java version will use REST + STOMP rather than raw Socket.IO events.
The conceptual mapping is part of each feature's plan in `progress/current.md`.

---

## Session hygiene

When you start a session:

- Read this file.
- Read your role definition in `.claude/agents/`.
- Check `progress/current.md`. If it contains an unfinished plan, ask the
  user whether to resume or close it.
- Check `feature_list.json`. Report counts: pending, in_progress, done.

When you end a session:

- Update `feature_list.json` if state changed.
- Append a brief entry to `progress/history.md`.
- Replace `progress/current.md` with a "session closed" note.

State outlives chat. Chat does not.
