# Session history

This is an append-only log of completed sessions, in chronological
order. Each entry corresponds to one feature being closed.

## Format

Each entry follows this shape:

```
## YYYY-MM-DD — <feature-id>

**Status:** done

**Summary:** One paragraph describing what was built, the approach
taken, and any notable decisions or trade-offs.

**Files touched:** comma-separated list, or a short bulleted list if
many files.

**Feature note:** `notes/NN-<feature-id>.md`.
```

## Entries

## 2026-05-14 — health-check

**Status:** done

**Summary:** First vertical slice of the project. Added a dedicated
`GET /api/health` endpoint returning `{ status, version, timestamp }`
through a `@RestController` instead of routing through Actuator, so the
contract is owned by the application. The `version` comes from
`BuildProperties` (auto-registered after wiring the
`spring-boot-maven-plugin:build-info` execution into `pom.xml`), with a
fallback to `"unknown"` via `ObjectProvider<BuildProperties>` so the
controller boots even without `build-info.properties`. The `timestamp`
uses an injected `Clock` (a `@Bean` defined in `config/ClockConfig`),
which the third IT swaps for `Clock.fixed(...)` via a nested
`@TestConfiguration` so determinism is testable without touching
production code. Two infrastructure fixes surfaced during
implementation and were folded in: `TestcontainersConfiguration` was
promoted to `public` (otherwise ITs in sub-packages cannot `@Import` it)
and `maven-failsafe-plugin` was declared explicitly in `pom.xml`
(without it, the parent's `pluginManagement` does not activate and
`./mvnw verify` silently skips all `*IT` tests — the previous "green
baseline" had never executed an IT). `application.properties` was
converted to `application.yml` to match `docs/conventions.md`.

**Files touched:**

- `src/main/java/io/github/dariogguillen/chess/config/ClockConfig.java` (new)
- `src/main/java/io/github/dariogguillen/chess/web/health/HealthController.java` (new)
- `src/main/java/io/github/dariogguillen/chess/web/health/HealthResponse.java` (new)
- `src/test/java/io/github/dariogguillen/chess/web/health/HealthControllerIT.java` (new, 3 tests)
- `src/test/java/io/github/dariogguillen/chess/TestcontainersConfiguration.java` (modified: promoted to public)
- `src/main/resources/application.yml` (new), `application.properties` (deleted)
- `pom.xml` (modified: added `build-info` execution and explicit `maven-failsafe-plugin`)
- `README.md` (modified: documented `/api/health` under a new "API" section)
- `feature_list.json` (modified: `health-check.status` → `done`)

**Feature note:** `notes/01-health-check.md`.

## 2026-05-15 — domain-models

**Status:** done

**Summary:** Introduced the core domain layer under
`io.github.dariogguillen.chess.domain` as pure records and enums, with
no Spring, persistence, or chesslib coupling. Two structural shapes:
enums (`Side`, `Piece`, `RoomStatus`, `GameStatus`) for closed sets of
labels, and records (`Square`, `Player`, `Move`, `Room`, `Game`) for
immutable value objects with invariants enforced at construction via
compact constructors. Key decisions: `Square` rejects uppercase rather
than normalizing (so two `Square`s built from different input strings
never compare equal under records' generated component-wise `equals`);
`Piece` carries all six chess piece kinds with an `isPromotionTarget()`
helper instead of splitting into a separate `PromotionPiece` enum
(single source of truth, avoids constant conversions in later
features); `Move` validates structurally that promotion is to `KNIGHT`,
`BISHOP`, `ROOK` or `QUEEN` (rejecting `PAWN`/`KING` as not well-defined,
which is distinct from chess legality and stays in this layer); `Room`
and `Game` defensively copy their collection fields via
`List.copyOf(...)` so callers cannot mutate them after construction.
Invariant violations raise `IllegalArgumentException` (or
`NullPointerException` via `Objects.requireNonNull`), reserving the
`exception/` hierarchy for HTTP-mapped business errors that will land
with the service layer. 60 new unit tests under `src/test/java/.../domain`,
no integration tests added (correct for pure-domain code per the refined
`docs/conventions.md`).

**Files touched:**

- `src/main/java/io/github/dariogguillen/chess/domain/Side.java` (new)
- `src/main/java/io/github/dariogguillen/chess/domain/Piece.java` (new)
- `src/main/java/io/github/dariogguillen/chess/domain/RoomStatus.java` (new)
- `src/main/java/io/github/dariogguillen/chess/domain/GameStatus.java` (new)
- `src/main/java/io/github/dariogguillen/chess/domain/Square.java` (new)
- `src/main/java/io/github/dariogguillen/chess/domain/Player.java` (new)
- `src/main/java/io/github/dariogguillen/chess/domain/Move.java` (new)
- `src/main/java/io/github/dariogguillen/chess/domain/Room.java` (new)
- `src/main/java/io/github/dariogguillen/chess/domain/Game.java` (new)
- `src/test/java/io/github/dariogguillen/chess/domain/SquareTest.java` (new, 27 tests)
- `src/test/java/io/github/dariogguillen/chess/domain/MoveTest.java` (new, 11 tests)
- `src/test/java/io/github/dariogguillen/chess/domain/RoomTest.java` (new, 11 tests)
- `src/test/java/io/github/dariogguillen/chess/domain/GameTest.java` (new, 11 tests)
- `feature_list.json` (modified: `domain-models.status` → `done`)

**Feature note:** `notes/02-domain-models.md`.
