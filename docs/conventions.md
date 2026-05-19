# Conventions

This document is the style guide for the codebase. When something is not
covered here, prefer the standard Spring Boot way and the Java community
norms.

---

## Package layout

The base package is `io.github.dariogguillen.chess`. Code is organized by
**technical role**, not by feature:

```
io.github.dariogguillen.chess
├── config/          # @Configuration classes
├── domain/          # Records, enums, pure domain logic
├── service/         # @Service classes (use cases)
├── web/             # @RestController + DTOs
├── websocket/       # STOMP @Controller + message DTOs
├── persistence/     # JPA entities + Spring Data repositories
├── cache/           # Spring Data Redis repositories + caches
└── exception/       # Exception hierarchy + global handler
```

Inside `web/`, group DTOs in a sub-package per resource:

```
web/
├── room/
│   ├── RoomController.java
│   ├── CreateRoomRequest.java
│   └── RoomResponse.java
└── game/
    ├── GameController.java
    ├── MoveRequest.java
    └── GameStateResponse.java
```

## Naming

- **Classes:** PascalCase. Be explicit (`CreateRoomRequest`, not
  `RoomReq`).
- **Methods and fields:** camelCase. Verbs for methods, nouns for fields.
- **Constants:** UPPER_SNAKE_CASE.
- **Type-bearing suffixes:** controllers end with `Controller`,
  services with `Service`, repositories with `Repository`,
  exceptions with `Exception`, integration tests with `IT`, unit
  tests with `Test`, DTOs with `Request` / `Response` /
  `Event` / `Message` (depending on direction).
- **Test names:** `should<Behavior>_when<Condition>` or
  `<methodName>_<scenario>`. Pick one and be consistent within a
  test class.

## DTOs

DTOs are **records**, always. Never expose JPA entities through
controllers or WebSocket messages.

```java
public record CreateRoomRequest(String displayName) {}

public record RoomResponse(
    String id,
    String creatorId,
    GameStatus status,
    List<PlayerSummary> players
) {}
```

Validation goes on the record components via Jakarta validation
annotations.

## Dependency injection

**Constructor injection only.** No `@Autowired` on fields. No setter
injection. Final fields:

```java
@Service
public class RoomService {

    private final RoomRepository rooms;
    private final ChessRules rules;

    public RoomService(RoomRepository rooms, ChessRules rules) {
        this.rooms = rooms;
        this.rules = rules;
    }
}
```

Lombok is not used. The verbosity is intentional and explicit.

## Transactions

`@Transactional` belongs on service methods that mutate state. Keep
transactions short and focused:

- Read-only paths do not need `@Transactional`.
- Do not put `@Transactional` on controllers.
- Do not nest service calls inside a single transaction unless
  intentional. Prefer composing services at the use-case level.

## JavaDoc

Required on:

- Public methods of services.
- Public methods of any class in `domain/` that other layers depend on.
- Custom exceptions (explain when they are thrown).

Optional on:

- Controllers (the OpenAPI/Swagger annotations cover them later).
- Test methods (the method name is the doc).
- Records (the fields are usually self-explanatory).

Format:

```java
/**
 * Applies a move to the given game.
 *
 * @param gameId the game identifier
 * @param move   the move to apply, in algebraic from-to form
 * @return the updated game state after the move
 * @throws GameNotFoundException if no game exists for the id
 * @throws NotYourTurnException  if it is not the requester's turn
 * @throws IllegalMoveException  if the move is rejected by chesslib
 */
GameState applyMove(String gameId, Move move);
```

## Exception handling

Custom exceptions extend a small hierarchy in `exception/`:

```
ChessException (abstract, RuntimeException)
├── NotFoundException
│   ├── RoomNotFoundException
│   └── GameNotFoundException
├── ConflictException
│   └── RoomFullException
└── UnprocessableException
    ├── IllegalMoveException
    └── NotYourTurnException
```

A single `@RestControllerAdvice` maps each exception type to an HTTP
status and a structured JSON response:

```json
{
  "error": "ILLEGAL_MOVE",
  "message": "The bishop cannot move to e5 from c1.",
  "timestamp": "2026-05-14T10:23:11Z"
}
```

Controllers do **not** wrap calls in try/catch.

## Logging

- Use SLF4J. Get loggers via `LoggerFactory.getLogger(MyClass.class)`.
- One logger per class. Do not share loggers across classes.
- **Structured logging.** Use parameterized messages, not string
  concatenation:

  ```java
  log.info("Move accepted: gameId={}, move={}", gameId, move);
  ```

- **Log levels:**
  - `ERROR` for unrecoverable failures the user should be alerted to.
  - `WARN` for unexpected but handled situations.
  - `INFO` for state changes and important events (game started,
    move accepted, game completed).
  - `DEBUG` for development detail. Off in production.

- Do not log secrets, full request bodies that might contain personal
  data, or stack traces at `INFO` level.

## Configuration

- All configuration lives in `src/main/resources/application.yml`.
- Local overrides go in `application-local.yml` (gitignored).
- Test overrides go in `src/test/resources/application-test.yml`.
- Secrets come from environment variables, never from `application.yml`.

```yaml
spring:
  datasource:
    url: ${POSTGRES_URL:jdbc:postgresql://localhost:5432/chess}
    username: ${POSTGRES_USER:chess}
    password: ${POSTGRES_PASSWORD:chess}
```

## Tests

The integration test is the baseline. The unit test is the supplement.
Both kinds live in `src/test/java/...`, mirroring the main package
layout.

- **Integration tests** end with `IT`. They boot the Spring context
  and use Testcontainers for real Postgres and Redis. Examples:
  `RoomServiceIT`, `GameControllerIT`. **Every feature ships with an
  IT** that exercises its user-visible behavior.
- **Unit tests** end with `Test`. They do not boot Spring. They are
  fast. Examples: `ChessRulesTest`, `RoomServiceTest` (with mocks).
  They are added **only when they earn their place**.

### When a unit test earns its place

Add a unit test when at least one of these applies:

- The logic has **non-trivial branching** that the IT cannot
  reasonably exercise. Canonical example for this project: `ChessRules`
  validating moves — castling, en passant, promotion, check, mate,
  stalemate are each a tight unit case and combining them into one IT
  would be costly and unclear.
- The code is **pure logic** that does not need a Spring context.
  Anything in `domain/` is a typical fit.
- There are **real edge cases** the IT does not touch that represent
  domain behavior (not cosmetic defensive fallbacks).
- There are **large input combinations** where one IT per case would
  be absurd. A unit test with parameterized inputs is the right tool.

### When a unit test is not needed

Do not add one for:

- Controllers that delegate to a service. The IT already covers
  routing + serialization + wiring.
- DTOs, records, and value objects without behavior.
- `@Configuration` classes whose `@Bean`s are trivial (e.g. a single
  `Clock.systemUTC()`). Spring guarantees the contract.
- Branches that are **defensive against situations that cannot occur
  in production** (e.g. a fallback to a constant when an
  always-present dependency is absent). The branch exists for
  robustness, not for behavior; cover it only if the situation is
  reachable in real deployments.

### Style for both flavors

- One assertion subject per test. Many small tests beat a few big
  ones.
- Tests for new features ship in the same commit/PR as the feature.
- Test method names follow `should<Behavior>_when<Condition>` or
  `<methodName>_<scenario>`. Pick one and stay consistent within a
  test class.

## Imports and formatting

- No wildcard imports.
- 100-column soft limit, 120 hard limit.
- Use the Spring Boot defaults for everything else.

### Fully-qualified class names

**Default: import every type you reference, regardless of which package
or library it comes from.** Fully-qualified names are noise — they
privilege provenance over semantics. This applies to our own packages
**and** to third-party libraries (chesslib, Spring, etc.) equally. A
signature like

```java
public MoveOutcome applyMove(String fen, io.github.dariogguillen.chess.domain.Move move)
```

is harder to read than

```java
import io.github.dariogguillen.chess.domain.Move;
// ...
public MoveOutcome applyMove(String fen, Move move)
```

and the same goes for an unnecessary `com.github.bhlangonijr.chesslib.Board`
inside a service body — `Board` is just as informative once imported.

**The single exception:** when two classes in different packages share a
simple name and both are referenced in the same file, Java requires that
at least one of them be fully-qualified. In that case:

- **Import the type from our own packages** (`io.github.dariogguillen.chess.*`).
  Our domain types are the primary subjects of our code; they get the
  import. They must never appear fully-qualified in our own signatures.
- The external library's type is fully-qualified **only at the specific
  sites where it would otherwise shadow our domain type** — typically
  inside the anti-corruption layer that wraps the library (e.g. private
  helpers in `ChessRules`).

**Fully-qualification is a per-type per-site decision, not a per-file
blanket.** Even inside a file that legitimately needs to fully-qualify
some types from a library (because of collisions), other types from the
same library that do **not** collide with anything must still be
imported and used by simple name. Example: in `ChessRules.java`,
chesslib's `Move`, `Square`, `Piece`, and `Side` collide with our domain
types — those stay fully-qualified inside helpers. chesslib's `Board`
and `PieceType` collide with nothing — those must be imported.

Spotless does not enforce this. The implementer applies the convention
while writing; the reviewer validates it during code walk by grepping
for fully-qualified references of any package — ours or third-party —
and confirming each match is justified by a real same-simple-name
collision in the same file.

## Maven

- Pin the Spring Boot version via the parent POM.
- Add dependencies one at a time, with a justification in the commit
  message.
- Keep `pom.xml` sorted by section: parent, properties, dependencies,
  build. Within dependencies, group: Spring Boot starters, Spring Data,
  third-party (chesslib), test.
