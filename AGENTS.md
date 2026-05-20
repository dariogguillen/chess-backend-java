# AGENTS.md — Map of this repository

This file is the entry point for any AI agent working on this project. It is a
map, not a reference document. It points to where information lives; you look
things up on demand instead of carrying everything in context.

---

## What this project is

`chess-backend-java` is a Java + Spring Boot backend for an online multiplayer
chess game. Two players connect through a shareable room and play in real
time. The frontend that consumes this API lives in a separate repo,
[`chess-frontend`](https://github.com/dariogguillen/chess-frontend), which
hosts a React + TypeScript + Vite SPA. The two repos coordinate around the API
contract; see "Cross-repo coordination" below.

This backend is a rewrite of an earlier Node + TypeScript implementation that
originally lived in `chess-frontend`'s monorepo predecessor (under a `backend/`
folder; the folder was removed on 2026-05-19 when the repo was flattened to a
frontend-only layout and renamed from `chess-game` to `chess-frontend`). The
historical Node source is preserved in the `refactor-base` branch of
`chess-frontend`.

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
   - The Java / Spring concepts to highlight in the feature note.
6. Wait for user approval of the plan unless instructed otherwise.
7. Hand the plan to `implementer`. The implementer produces code, tests,
   and the feature note in `notes/NN-<feature-id>.md`.
8. Run `./init.sh`. All steps must pass.
9. Hand the work to `reviewer`. The reviewer validates against
   `CHECKPOINTS.md`.
10. If the reviewer rejects, return to step 7 with specific issues.
11. When the reviewer approves, mark the feature as `done` in
    `feature_list.json`.
12. Append a one-paragraph entry to `progress/history.md`.
13. Replace `progress/current.md` with a "session closed" note.

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
| Feature learning notes | `notes/` |
| Feature note template | `notes/_template.md` |

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

## Feature notes

Every feature ships with a learning note at `notes/NN-<feature-id>.md`,
where `NN` is the zero-padded priority from `feature_list.json`. The
implementer produces it, the reviewer validates it. The reviewer rejects
the feature if the note is missing or empty.

The note is written for a reader who knows Scala/Typelevel and Node but
is learning Java + Spring Boot. It documents what was built, the
concepts involved, the decisions taken, and the cross-ecosystem
comparisons.

See `notes/_template.md` for the structure each note must follow.

---

## Cross-repo coordination

This backend's REST and STOMP surfaces are consumed by the
[`chess-frontend`](https://github.com/dariogguillen/chess-frontend) repo.
When a feature in this repo changes the contract — a new endpoint, a new
STOMP topic, a DTO shape, an error code — the plan in
`progress/current.md` must reference whether the frontend side needs
coordination, and document the change in `docs/architecture.md`'s
"API contract" section so the frontend can align.

The canonical sources of truth for the REST surface are:

- `GET /v3/api-docs` — machine-readable OpenAPI 3 JSON spec served by
  the running application (feature 4.5 added springdoc).
- `GET /swagger-ui.html` — interactive UI for exploration.

The frontend's typed API client aligns to those specs via
`openapi-typescript` codegen (planned in the frontend's feature 3). STOMP
topics and message shapes are documented in `docs/architecture.md` and
mirrored in the frontend's own `docs/architecture.md`. When STOMP
contracts change, both architecture docs update in coordination.

The frontend operates on its own harness (leader / implementer / reviewer)
and its own `feature_list.json`. The two repos do not share git history
or a monorepo; coordination happens through the contract docs and the
discipline enforced by each side's leader role at planning time. Feature
5 of `chess-backend-java` (`game-rest-api`) and feature 4.5 (`api-docs`)
were closed with this discipline already in place; future features that
touch the contract should continue it.

---

## Reference: the historical Node backend

The Node backend that originally lived alongside this code — in the
predecessor of `chess-frontend`, when that repo was still called
`chess-game` and contained both frontend and backend — is a behavioral
specification, not a code reference. Its source is preserved in the
`refactor-base` branch of `chess-frontend` (`backend/src/index.ts` on
that branch). Treat its socket events as the source of truth for *what*
the system does, not *how* it should be coded.

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

The Java version uses REST + STOMP rather than raw Socket.IO events. The
conceptual mapping was developed feature by feature in `progress/current.md`
and `progress/history.md`.

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
