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

## 2026-05-15 — chesslib-integration

**Status:** done

**Summary:** Introduced `ChessRules` as a `@Service` in
`io.github.dariogguillen.chess.service` that wraps the `chesslib`
library and exposes a single method
`MoveOutcome applyMove(String fen, Move move)`. The service is the
only file in the codebase that imports `com.github.bhlangonijr.chesslib`
— a strict anti-corruption layer that translates between domain types
(`Move`, `Square`, `Piece`, `GameStatus`) and chesslib's API. The
return type `MoveOutcome` is a record `{ boolean legal, String fen,
GameStatus status }` with a uniform contract: `fen` always describes
the current state of the board (post-move when legal, the input fen
when illegal or unparseable). Implementation surfaced one real bug in
chesslib's API: `Board.doMove(move, true)` only validates structural
integrity, accepting moves like `e2-e5` from the starting position;
the fix uses `board.legalMoves().contains(move)` as the legality
ground truth, with the `illegalMove_*` test as the canary. The chesslib
dependency is pulled from JitPack (1.3.6) because the library is not
published on Maven Central. 13 unit tests cover every chess rule
required by the acceptance criteria: legal/illegal moves, check,
fool's mate, stalemate, kingside and queenside castling, en passant,
queen promotion, parameterized underpromotion to KNIGHT/BISHOP/ROOK,
and invalid FEN. As part of closing the session, the leader removed
`src/test/java/.../ChessApplicationTests.java` — the Spring Initializr
scaffold smoke test that was redundant with `HealthControllerIT`
(which also boots the full context with Testcontainers).

**Files touched:**

- `src/main/java/io/github/dariogguillen/chess/service/ChessRules.java` (new)
- `src/main/java/io/github/dariogguillen/chess/service/MoveOutcome.java` (new)
- `src/test/java/io/github/dariogguillen/chess/service/ChessRulesTest.java` (new, 13 tests)
- `src/test/java/io/github/dariogguillen/chess/ChessApplicationTests.java` (deleted: redundant scaffold smoke test)
- `pom.xml` (modified: added JitPack repository and `com.github.bhlangonijr:chesslib:1.3.6`)
- `feature_list.json` (modified: `chesslib-integration.status` → `done`)

**Feature note:** `notes/03-chesslib-integration.md`.

## 2026-05-16 — chesslib-integration (reopened and re-closed)

**Status:** done

**Summary:** The feature was reopened the day after the original
close because the user, while reading the implementation during
validation, noticed that `ChessRules#applyMove` constructed a fresh
chesslib `Board` on every call and only invoked `loadFromFen(fen)`.
A FEN does not carry position history, so chesslib's `board.isDraw()`
could never detect threefold repetition — `mapStatus(...)` would
silently return `ONGOING` for what was actually a draw. The existing
test suite could not catch the bug because the previous API only
took `(fen, move)`, leaving no place to express a multi-move
scenario. The fix introduces a new value type
`service/GameState` (record of `startingFen`, `history`, cached
`currentFen`, cached `currentStatus`) and reshapes `ChessRules` to
two operations: `initialState(String startingFen) → GameState` and
`applyMove(GameState, Move) → MoveOutcome`. Every `applyMove` call
loads the starting FEN, replays the entire move history on a fresh
`Board`, then evaluates the candidate move — preserving chesslib's
internal position-hash history at the cost of a sub-millisecond
replay per call. `MoveOutcome` was reshaped to
`record MoveOutcome(boolean legal, GameState state)`, with the
contract that `state` is the post-move state when legal and the
input state unchanged when illegal. `initialState` throws
`IllegalArgumentException` on unparseable FEN — a programmer error,
distinct from an illegal move at runtime, replacing the previous
`legal = false` conflation. `ChessRulesTest` grew to 15 tests:
every prior scenario adapted to the new API, plus the canary
`threefoldRepetition_returnsDrawStatus` (which exercises
`Nf3 Nc6 Ng1 Nb8` repeated three times and asserts `DRAW`, a test
that was impossible to write under the old API) and the
`replayingHistoryProducesExpectedFen` sanity check. Three
discarded options for `GameState` shape (opaque class with
long-lived `Board`, plain tuple parameters, full domain `Game`
threading) were documented and rejected in favor of the record;
notes capture the rationale. The SLF4J logger in `ChessRules` was
removed alongside the refactor — with FEN validation moved to
`initialState`, there is no longer a code path where `applyMove`
receives a malformed FEN, so the warn-log no longer made sense.

A second loop opened mid-validation when the user noticed
`src/main/java/.../service/ChessRules.java` still used chesslib
types fully-qualified at sites where no name collision with our
domain existed (`com.github.bhlangonijr.chesslib.Board`,
`...PieceType`). Root cause was in the harness, not the code: the
`docs/conventions.md` section "Fully-qualified class names" was
written around the collision case and left the general "prefer
imports" principle implicit, so the previous reviewer's grep only
covered `io.github.dariogguillen.chess.domain.*`. The convention
was rewritten to make the universal rule explicit and the
collision case the single acotada exception ("per-type per-site,
not per-file blanket"). `CHECKPOINTS.md` gained a bullet that
requires every fully-qualified reference in main code, regardless
of package, to be justified by a same-simple-name collision in the
same file. `.claude/agents/reviewer.md` gained a "Concrete checks
worth scripting" section with the grep recipe (covering ours,
chesslib, Spring, and any third-party prefix in use). The
implementer then cleaned `ChessRules.java`: added two imports
(`Board`, `PieceType`) and replaced the unqualified occurrences,
while preserving the four chesslib types whose simple names
genuinely collide with our domain (`Move`, `Square`, `Piece`,
`Side` — the last kept fully-qualified preventively, deliberately,
with an out-of-scope observation noted by the reviewer). The
memory `feedback-no-fully-qualified-names.md` was reframed the
same way.

A third local-tooling change happened in the same session but
outside the repo: the user's neovim/lazyvim setup
(`/home/dariogg/Documents/dotfiles/lazyvim`) was wired to
`conform.nvim` running `google-java-format-1.22.0-all-deps.jar`
(matching the Spotless version pinned in `pom.xml:162`) so saves
from neovim produce output byte-identical to `./mvnw spotless:apply`,
removing the prior fail loop where jdtls's Eclipse JDT formatter
would reformat on save and `./init.sh` would fail on
`spotless:check`. The plugin file lives at
`/home/dariogg/Documents/dotfiles/lazyvim/lua/plugins/java.lua`
and the jar at `~/.local/share/google-java-format/`. No repo
change for this — pure local setup — but worth recording for
future onboarding.

**Files touched:**

- `src/main/java/io/github/dariogguillen/chess/service/GameState.java` (new)
- `src/main/java/io/github/dariogguillen/chess/service/MoveOutcome.java` (modified: reshaped to `(boolean legal, GameState state)`)
- `src/main/java/io/github/dariogguillen/chess/service/ChessRules.java` (modified: new API `initialState` + `applyMove(GameState, Move)`, replay loop, SLF4J removed, fully-qualified cleanup)
- `src/test/java/io/github/dariogguillen/chess/service/ChessRulesTest.java` (modified: 15 tests under new API, including `threefoldRepetition_returnsDrawStatus` and `replayingHistoryProducesExpectedFen`)
- `docs/architecture.md` (modified: 6-line note on service-level value types)
- `docs/conventions.md` (modified: "Fully-qualified class names" section rewritten — universal rule first, collision as the single per-site exception)
- `CHECKPOINTS.md` (modified: new bullet under `### Code` for fully-qualified names of any package origin; split closing tasks under post-approval + user sign-off)
- `.claude/agents/reviewer.md` (modified: new "Concrete checks worth scripting" section with the grep recipe)
- `.claude/agents/leader.md` (modified earlier in session: planning section requires explicit README/architecture impact answer; closing requires user explicit OK)
- `.claude/settings.json` (modified earlier: hook schema fix)
- `notes/03-chesslib-integration.md` (modified: "Decisions taken" + "Gotchas" appended with `*(added 2026-05-15)*` markers covering the GameState refactor)
- `pom.xml` (modified earlier: chesslib 1.3.6 + JitPack + actuator + spotless + failsafe + build-info + CVE overrides for commons-lang3 3.18.0 and commons-compress 1.27.1 test-scope)
- `feature_list.json` (modified: `chesslib-integration.status` → `done`)

**Feature note:** `notes/03-chesslib-integration.md`.

## 2026-05-18 — room-rest-api

**Status:** done

**Summary:** First feature shipping HTTP endpoints to real users.
Two operations under `/api/rooms`: `POST` creates a room with the
caller as White (in `WAITING_FOR_PLAYER`), `POST /{id}/join`
joins the second player as Black, mutates the room to `ACTIVE`,
**and creates the `Game` in the same atomic step**. The second
behaviour was a deliberate decision against splitting "join" and
"start" into separate endpoints; the user confirmed the
trade-off in the planning phase. Room ids are 6-char short codes
from the alphabet `ABCDEFGHJKMNPQRSTUVWXYZ23456789` (no
visually-ambiguous chars), generated via `SecureRandom`, with up
to 5 collision retries before the service throws an internal
`IllegalStateException`. Player ids are server-generated UUIDs.
The atomic "read room → mutate room → write game" sequence runs
inside `ConcurrentHashMap.compute(roomId, …)` so a losing
concurrent joiner sees the room as full and surfaces
`RoomFullException`, never a half-state; the game write happens
inside the lambda which preserves the cross-store invariant "a
`Game` exists iff its `Room` is `ACTIVE`". `RoomStore` and
`GameStore` exist as interfaces with `InMemoryRoomStore` /
`InMemoryGameStore` as the only registered beans today — the
seam that feature 7's Redis swap will pivot on without touching
the consuming `RoomService`. The `exception/` hierarchy lands
for the first time (`ChessException` → `NotFoundException` /
`ConflictException` with concrete `RoomNotFoundException` and
`RoomFullException`), as does the first `@RestControllerAdvice`
(`GlobalExceptionHandler`), which derives the response body's
`error` code mechanically from the exception's simple name
(`RoomFullException` → `ROOM_FULL`) so future exceptions plug
in without per-class branches. Two framework exceptions
(`MethodArgumentNotValidException` for `@Valid` failures,
`HttpMessageNotReadableException` for malformed JSON) get
explicit handlers with hardcoded codes (`VALIDATION_FAILED`,
`MALFORMED_REQUEST`). `ErrorResponse` is a `record(String error,
String message, Instant timestamp)`; timestamp uses the injected
`Clock` so error bodies are deterministic in tests. `ChessRules`
gained `standardInitialState()` (returns the starting position
as a `GameState`) so `RoomService` does not have to import
chesslib to spell the standard FEN — the anti-corruption
boundary stays strict.

Three late additions landed on top of the initial close,
before flipping to `done`:

1. **Validation starter discovery (mid-implementation).**
   `spring-boot-starter-validation` does not resolve
   `jakarta.validation.constraints.NotBlank` transitively from
   `starter-web` in Spring Boot 3.5.14; the implementer added
   the starter explicitly to `pom.xml`. The `@Valid`/`@NotBlank`
   wiring is proven by `createRoom_blankDisplayName_returns400`.

2. **`Constants.startStandardFENPosition` instead of
   `Board.STARTING_POSITION_FEN`.** The plan referenced
   `Board.STARTING_POSITION_FEN`, but chesslib 1.3.6 places the
   constant at `com.github.bhlangonijr.chesslib.Constants.startStandardFENPosition`.
   Verified with `javap` on the jar. `ChessRules.java` imports
   `Constants` and uses the simple name; `Board.STARTING_POSITION_FEN`
   does not exist on this version.

3. **Case-insensitive room id lookup (user catch during Insomnia
   validation, 2026-05-18).** Shared short codes are read,
   dictated, and retyped by humans; requiring exact-case typing
   was brittle and inconsistent with the alphabet decision (we
   already excluded `O`/`I`/`L`/`0`/`1` for visual ambiguity).
   `RoomController` normalizes the `{id}` path variable via
   `id.toUpperCase(Locale.ROOT)` before calling
   `RoomService.joinRoom`. Storage and responses always use the
   canonical uppercase form — the client never sees two
   representations. The rule is roomId-only: other ids in later
   features (`gameId`, `playerId`) are server-generated UUIDs,
   not human-shared, and stay case-sensitive. Documented in
   `notes/04-room-rest-api.md` under "Gotchas".

4. **Removal of the unreachable third branch (user catch,
   2026-05-18).** The `compute` lambda in `RoomService.joinRoom`
   originally had three checks; the third
   (`existing.status() != WAITING_FOR_PLAYER` →
   `RoomAlreadyStartedException`) was not reachable from any
   HTTP path because no production code today produces
   `RoomStatus.CLOSED`. The test that "covered" it only worked
   by seeding `CLOSED` directly through the autowired
   `RoomStore`. Per `docs/conventions.md` ("Branches that are
   defensive against situations that cannot occur in
   production … do not require a unit test") and the project
   preface ("Don't design for hypothetical future requirements"),
   the branch, the exception class, the test, and all doc
   references were removed. Feature 9 (`disconnect-handling`)
   is the first place expected to produce `CLOSED` from a real
   path; it will reintroduce the appropriate check (possibly
   with a different exception name matching abandonment
   semantics) along with a real HTTP-path test.

One reviewer observation flagged but not corrected: the feature
note's "Java/Spring concepts" section said the validation
starter "is already on the classpath transitively via
`spring-boot-starter-web` — see 'Decisions taken' for the
verification step", but "Decisions taken" did not circle back
to validation. The actual `pom.xml` and the IT are correct;
this is a minor doc cross-reference inconsistency, not a
behaviour issue.

**Final test counts:** surefire 80 (`ChessRulesTest` 16,
`RoomCodeGeneratorTest` 4, domain tests 60). Failsafe 10
(`HealthControllerIT` 3, `RoomControllerIT` 7). Grand total 90.

**Files touched:**

- `src/main/java/io/github/dariogguillen/chess/exception/ChessException.java` (new)
- `src/main/java/io/github/dariogguillen/chess/exception/NotFoundException.java` (new)
- `src/main/java/io/github/dariogguillen/chess/exception/ConflictException.java` (new; JavaDoc cleaned of removed `RoomAlreadyStartedException` reference in late addition #4)
- `src/main/java/io/github/dariogguillen/chess/exception/RoomNotFoundException.java` (new)
- `src/main/java/io/github/dariogguillen/chess/exception/RoomFullException.java` (new)
- `src/main/java/io/github/dariogguillen/chess/exception/ErrorResponse.java` (new)
- `src/main/java/io/github/dariogguillen/chess/exception/GlobalExceptionHandler.java` (new)
- `src/main/java/io/github/dariogguillen/chess/service/RoomCodeGenerator.java` (new)
- `src/main/java/io/github/dariogguillen/chess/service/RoomStore.java` (new)
- `src/main/java/io/github/dariogguillen/chess/service/InMemoryRoomStore.java` (new)
- `src/main/java/io/github/dariogguillen/chess/service/GameStore.java` (new)
- `src/main/java/io/github/dariogguillen/chess/service/InMemoryGameStore.java` (new)
- `src/main/java/io/github/dariogguillen/chess/service/RoomService.java` (new; lambda trimmed from three checks to two in late addition #4)
- `src/main/java/io/github/dariogguillen/chess/service/ChessRules.java` (modified: `standardInitialState()` added)
- `src/main/java/io/github/dariogguillen/chess/web/room/RoomController.java` (new; `Locale.ROOT` normalization added in late addition #3)
- `src/main/java/io/github/dariogguillen/chess/web/room/CreateRoomRequest.java` (new)
- `src/main/java/io/github/dariogguillen/chess/web/room/JoinRoomRequest.java` (new)
- `src/main/java/io/github/dariogguillen/chess/web/room/RoomResponse.java` (new)
- `src/test/java/io/github/dariogguillen/chess/service/RoomCodeGeneratorTest.java` (new, 4 tests)
- `src/test/java/io/github/dariogguillen/chess/web/room/RoomControllerIT.java` (new, 7 tests after late addition #4)
- `src/test/java/io/github/dariogguillen/chess/service/ChessRulesTest.java` (modified: `standardInitialState_returnsStartingPositionWithEmptyHistory` added — total 16)
- `pom.xml` (modified: `spring-boot-starter-validation` added)
- `README.md` (modified: two new endpoints, case-insensitive note, error codes)
- `docs/architecture.md` (modified: paragraph on `RoomStore`/`GameStore` interface seam for feature 7)
- `docs/conventions.md` (modified: `RoomAlreadyStartedException` entry trimmed from the exception-hierarchy diagram in late addition #4)
- `notes/04-room-rest-api.md` (new; updated through three late additions)
- `feature_list.json` (modified: `room-rest-api.status` → `done`; new `api-docs` entry inserted at priority 4.5)

**Feature note:** `notes/04-room-rest-api.md`.

## 2026-05-18 — api-docs

**Status:** done

**Summary:** Adopted `springdoc-openapi-starter-webmvc-ui` 2.8.6
to auto-generate the OpenAPI 3 spec from existing REST
controllers and mount Swagger UI for interactive exploration.
The application now serves the machine-readable spec at
`/v3/api-docs` and the interactive UI at `/swagger-ui.html`
(which springdoc 302-redirects to `/swagger-ui/index.html`).
The three pre-existing endpoints (`GET /api/health`,
`POST /api/rooms`, `POST /api/rooms/{id}/join`) were annotated
inline: `@Tag` per controller, `@Operation(summary, description)`
per handler, one `@ApiResponse` per status code the handler can
produce — cross-checked against `GlobalExceptionHandler` so the
spec is honest about what the API actually returns. Every 4xx
references `ErrorResponse` via
`@Schema(implementation = ErrorResponse.class)`, defined once
and reused from every error site. DTO `@Schema` annotations
are selective — alphabet rules on `roomId`, UUID formats on ids,
`nullable = true` on `gameId`, examples where they aid Swagger
UI exploration. Trivial fields (`message`, blank-eligible
`displayName` beyond its `@NotBlank`) stay unannotated. The
top-level `Info` (title, description, version) is built via
`@Bean OpenAPI` in `config/OpenApiConfig`, reading the version
from `BuildProperties` via `ObjectProvider` with a graceful
`"unknown"` fallback — the same pattern `HealthController`
already uses. The README's per-endpoint manual contracts and
the error-codes summary table were **removed** in favor of two
links (`/swagger-ui.html` and `/v3/api-docs`) and one curl per
endpoint for paste-and-run convenience. `docs/architecture.md`
gained an "API contract" subsection naming the code-first
approach. The case-insensitive note from feature 4 was
preserved.

`OpenApiIT` adds three integration tests: well-formedness of
`/v3/api-docs` (paths present, schemas present, every 4xx
references `ErrorResponse` via `$ref`), Swagger UI redirect
(`/swagger-ui.html` → 302 → `/swagger-ui/index.html` → 200 +
`text/html`), and the **canary**
`apiDocs_includesOperationSummaries` which walks every operation
in the spec and asserts a non-empty `summary`. The canary is
the durable defense: a future endpoint shipped without
`@Operation(summary = …)` fails the build, by design. The walk
uses `paths.fields()` × nested `methodEntries.fields()` so new
endpoints get coverage automatically.

The feature was preceded by a **harness update** codifying
the springdoc convention as a first-class rule, not just the
shape of this one feature. The convention lives in three
places, mirroring the pattern established for "Fully-qualified
class names" in feature 3:

- `docs/conventions.md` → new section **"API documentation"**
  between "Exception handling" and "Logging". Documents the
  controller annotation policy, `ErrorResponse` as the
  canonical 4xx schema, selective `@Schema` on DTOs, top-level
  `@Bean OpenAPI` reading `BuildProperties`, and verification
  via the canary IT.
- `CHECKPOINTS.md` → new section **"API documentation (if the
  feature adds or modifies REST endpoints)"** between "Errors"
  and "Logging". Seven verifiable bullets, gated by the "if
  the feature ships `@RestController` changes" clause so
  pure-domain features skip the section.
- `.claude/agents/reviewer.md` → new subsection **"Springdoc
  API documentation"** under "Concrete checks worth scripting".
  Six-step grep recipe with concrete commands for inspecting
  `@Tag` / `@Operation` / `@ApiResponse` / `@Schema` on touched
  files, cross-checking against `GlobalExceptionHandler`, and
  loading the running spec for visual confirmation.

From feature 5 onwards the convention is the source of truth;
the implementer reads `docs/conventions.md` once and applies it
without each new feature having to re-derive the rules.

Five implementer-extra decisions were made during execution,
all justified and recorded in the feature note's "Gotchas":

1. **Explicit `mediaType = MediaType.APPLICATION_JSON_VALUE` on
   every `@Content`.** Without it, springdoc emits the schema
   under `*/*` rather than `application/json`, and the
   `$ref` lookup at `content.application/json.schema.$ref`
   fails in `OpenApiIT`. Applied uniformly across `RoomController`
   and `HealthController` for consistency.
2. **Path template form `{id}`** (springdoc's emit), confirmed
   in the test against `/api/rooms/{id}/join`.
3. **`/swagger-ui.html` redirect handling.** Springdoc serves a
   302 to `/swagger-ui/index.html`; the IT does two MockMvc
   calls — `is3xxRedirection()` + `redirectedUrl(…)` on the
   first, then `200 + text/html` on the redirect target.
4. **Temporary `SpecDumpHelper.java`** was created during
   implementation to capture spec excerpts for the report, then
   deleted. `find -name "SpecDumpHelper*"` returns empty.
5. **Spotless multi-line `@Schema` formatting.** Google Java
   Format wraps annotation arguments at the 100-col soft limit,
   producing wordier blocks in the five touched DTOs. Cosmetic;
   reads cleanly.

**Final test counts:** surefire 80 (`ChessRulesTest` 16,
`RoomCodeGeneratorTest` 4, domain tests 60). Failsafe 13
(`HealthControllerIT` 3, `RoomControllerIT` 7, `OpenApiIT` 3).
Grand total 93.

**Files touched:**

- `src/main/java/io/github/dariogguillen/chess/config/OpenApiConfig.java` (new; `@Bean OpenAPI` with `BuildProperties` fallback)
- `src/main/java/io/github/dariogguillen/chess/web/health/HealthController.java` (modified: `@Tag`, `@Operation`, `@ApiResponse`)
- `src/main/java/io/github/dariogguillen/chess/web/health/HealthResponse.java` (modified: `@Schema` on all three components)
- `src/main/java/io/github/dariogguillen/chess/web/room/RoomController.java` (modified: `@Tag` class-level, full `@Operation` + `@ApiResponse` matrix on both handlers; explicit `MediaType.APPLICATION_JSON_VALUE` everywhere)
- `src/main/java/io/github/dariogguillen/chess/web/room/CreateRoomRequest.java` (modified: `@Schema(example = "Alice")` on `displayName`)
- `src/main/java/io/github/dariogguillen/chess/web/room/JoinRoomRequest.java` (modified: `@Schema(example = "Bob")` on `displayName`)
- `src/main/java/io/github/dariogguillen/chess/web/room/RoomResponse.java` (modified: `@Schema` on all four components, `nullable = true` on `gameId`)
- `src/main/java/io/github/dariogguillen/chess/exception/ErrorResponse.java` (modified: class-level `@Schema(name = "ErrorResponse")` + `@Schema` on `error`)
- `src/test/java/io/github/dariogguillen/chess/config/OpenApiIT.java` (new, 3 tests including the canary)
- `pom.xml` (modified: `springdoc-openapi-starter-webmvc-ui:2.8.6` added in the third-party block)
- `README.md` (modified: manual per-endpoint table and error codes summary removed; "API documentation" subsection with both URLs added; one curl per endpoint preserved)
- `docs/architecture.md` (modified: "API contract" subsection under "Layered architecture")
- `docs/conventions.md` (modified earlier in session: new "API documentation" section between "Exception handling" and "Logging")
- `CHECKPOINTS.md` (modified earlier in session: new "API documentation (if the feature adds or modifies REST endpoints)" section between "Errors" and "Logging")
- `.claude/agents/reviewer.md` (modified earlier in session: new "Springdoc API documentation" subsection under "Concrete checks worth scripting")
- `notes/04.5-api-docs.md` (new; filename uses `04.5-` per the priority)
- `feature_list.json` (modified: `api-docs.status` → `done`)

**Feature note:** `notes/04.5-api-docs.md`.

## 2026-05-19 — game-rest-api

**Status:** done

**Summary:** Two HTTP endpoints close the game lifecycle until
WebSocket arrives in feature 6: `POST /api/games/{id}/moves`
applies a move (caller identified via `X-Player-Id` header,
validated by `ChessRules`, atomic per game), and `GET /api/games/{id}`
reads the current state (no auth on read — feature 6.5 spectators
will rely on this). The service is `GameService`, mirroring
`RoomService`'s pattern: stateless, orchestrates `GameStore` +
`ChessRules` inside a single `ConcurrentHashMap.compute(gameId, …)`
block so a losing concurrent move sees the post-move state and
surfaces `NotYourTurnException` rather than a half-state. The
domain `Game` record gained an immutable `startingFen` field
(between `black` and `fen`) so `ChessRules.applyMove(GameState, Move)`
can rebuild the position with full history-replay for
threefold-repetition detection; `RoomService.joinRoom` passes the
same value to both `startingFen` and `fen` at game creation.
`GameStatus.isTerminal()` was added as a pure-enum helper used
once in the move path to reject moves on ended games before the
turn check. Five new exceptions land in the hierarchy:
`UnprocessableException` (new abstract type for 422, the
forward-looking entry already documented in
`docs/conventions.md`'s diagram) with two concretes
`IllegalMoveException` and `NotYourTurnException`; plus
`GameNotFoundException` under `NotFoundException` and
`GameAlreadyEndedException` under `ConflictException`. The
mechanical `codeOf` derivation in `GlobalExceptionHandler`
produces `ILLEGAL_MOVE` / `NOT_YOUR_TURN` / `GAME_NOT_FOUND` /
`GAME_ALREADY_ENDED` from the simple class names — no per-class
branches added. Two new framework handlers complete the picture:
`UnprocessableException` → 422, `MissingRequestHeaderException`
→ 400 with hardcoded `MISSING_HEADER` so the response body shape
remains the canonical `ErrorResponse` envelope even when Spring
itself rejects the request.

This is the **first feature** that lived entirely under the
springdoc convention codified in feature 4.5 — every new
`@RestController` class and every new endpoint handler shipped
with its `@Tag`, `@Operation`, and full `@ApiResponse` matrix
from the initial implementer pass, plus selective `@Schema`
annotations on DTOs. The convention pays off: zero retroactive
annotation work, the canary IT
`apiDocs_includesOperationSummaries` passes without test
changes, and the OpenAPI components.schemas block now reflects
`GameStateResponse`, `MoveRequest`, `MoveSummary` (post-cleanup),
plus the existing entries from feature 4.5.

The `GameStore` interface gained a `compute(String, BiFunction)`
method mirroring `RoomStore.compute` — added by the implementer
as a justified decision beyond the brief because the original
plan called `gameStore.compute(...)` without first declaring the
seam. Six implementer decisions beyond the brief landed in the
initial pass; the reviewer validated each (promotion wire format
uses full enum names like `KNIGHT`, `Enum::name` for round-trip
on output, no `Locale.ROOT` because gameIds are case-sensitive
UUIDs, no extra `@Schema`/`@Parameter` on `@RequestHeader`
because springdoc reflects it, and the `turn` derivation in a
private static mapper rather than a separate `GameMapper`
class).

A second iteration of the feature applied **two architectural
cleanups before close**, driven by the user's architectural
audit during validation:

1. **`MoveDto.java` removed; replaced by a nested
   `public record MoveSummary` inside `GameStateResponse`.** The
   suffix `Dto` is not in the official list
   (`Request`/`Response`/`Event`/`Message`), and a record whose
   only purpose is to be a sub-shape of another record (`MoveDto`
   had no reference site outside `List<MoveDto> moves`) is
   idiomatic in Java 17+ as a nested record on the parent. The
   wire format did not change — the JSON keys remain
   `from`/`to`/`promotion`. The OpenAPI `components.schemas`
   entry renamed silently from `MoveDto` to `MoveSummary`. The
   only externally visible side effect of the refactor is that
   component schema name; no breaking change to consumers
   reading the JSON.

2. **`InMemoryRoomStore` and `InMemoryGameStore` moved from
   `service/` to `cache/`.** The package convention in
   `docs/conventions.md` → "Package layout" already named
   `cache/` as the home for Spring Data Redis repositories and
   caches; the in-memory implementations are the first
   inhabitants of `cache/`, and feature 7's Redis-backed siblings
   will land alongside them without further restructuring. The
   interfaces (`RoomStore`, `GameStore`) stay in `service/` — they
   are the port the service layer consumes. The two moved
   classes changed annotation from `@Service` to `@Component` to
   match the new location's semantics: these are adapters, not
   service-layer use cases. Spring DI resolves the implementation
   by interface type regardless of package, so no consumer or
   test needed a code change beyond the two moved files
   themselves. `docs/architecture.md` received a surgical update
   on lines 74-82 to fix the now-inverted comparative sentence
   (interfaces in `service/`, adapters in `cache/`).

Both cleanups were anchored to a broader discussion about the
codebase layout, in which the user also raised whether two
records with the same shape (`MoveRequest` and the
since-renamed-and-relocated `MoveSummary`) constituted
duplication. The discussion concluded that direction-based
separation (Request for input + Jakarta validation, response DTO
for output + selective `@Schema`) is a deliberate pattern, not
accidental duplication — the two types are likely to diverge as
`MoveSummary` is later enriched with fields the response cares
about (move number, side, SAN notation, etc.) that the request
does not. The records dispersed across `domain/`, `service/`,
and `web/` were validated as deliberate layer separation
(domain value objects, service-internal value types, wire-format
DTOs); no records were moved or merged.

Three follow-up sub-sections were identified for `docs/conventions.md`
and deferred to later features:
`@ConfigurationProperties` type-safe binding (apply when feature
7 introduces Redis TTL configuration), Spring Boot test slices
(apply when a slice test earns its place), and JPA projections
(apply in feature 8 alongside the first JPA entity). External
skills from `skills.sh` were evaluated and not adopted — the
catalog has nothing that fits our stack better than
`docs/conventions.md` already does.

A minor doc cleanup applied at the very end: the "File map → New
files" section of `notes/05-game-rest-api.md` originally listed
`MoveDto.java` as a new file; the entry was removed and the
`GameStateResponse.java` entry was extended to mention the
nested `MoveSummary` record, keeping the note factually correct
after the closing iteration.

**Final test counts:** surefire **82** (`ChessRulesTest` 16,
`RoomCodeGeneratorTest` 4, `GameTest` 13, `RoomTest` 11,
`SquareTest` 27, `MoveTest` 11). Failsafe **20**
(`HealthControllerIT` 3, `RoomControllerIT` 7, `OpenApiIT` 3,
`GameControllerIT` 7). Grand total **102** (was 93 after
feature 4.5; delta +9 = +2 in `GameTest` for the new
`startingFen` invariant tests, +7 in the new `GameControllerIT`).

**Files touched:**

- `src/main/java/io/github/dariogguillen/chess/domain/Game.java` (modified: added `startingFen` field with null + blank invariant)
- `src/main/java/io/github/dariogguillen/chess/domain/GameStatus.java` (modified: added `isTerminal()` helper)
- `src/main/java/io/github/dariogguillen/chess/service/GameService.java` (new)
- `src/main/java/io/github/dariogguillen/chess/service/GameStore.java` (modified: added `compute(String, BiFunction)` to the interface)
- `src/main/java/io/github/dariogguillen/chess/service/RoomService.java` (modified: `joinRoom` passes `startingFen`)
- `src/main/java/io/github/dariogguillen/chess/cache/InMemoryGameStore.java` (moved from `service/`; implements new `compute`; `@Service` → `@Component`)
- `src/main/java/io/github/dariogguillen/chess/cache/InMemoryRoomStore.java` (moved from `service/`; `@Service` → `@Component`)
- `src/main/java/io/github/dariogguillen/chess/exception/UnprocessableException.java` (new; abstract, mapped to 422)
- `src/main/java/io/github/dariogguillen/chess/exception/IllegalMoveException.java` (new)
- `src/main/java/io/github/dariogguillen/chess/exception/NotYourTurnException.java` (new)
- `src/main/java/io/github/dariogguillen/chess/exception/GameNotFoundException.java` (new)
- `src/main/java/io/github/dariogguillen/chess/exception/GameAlreadyEndedException.java` (new)
- `src/main/java/io/github/dariogguillen/chess/exception/GlobalExceptionHandler.java` (modified: 2 new handlers — `UnprocessableException` and `MissingRequestHeaderException`)
- `src/main/java/io/github/dariogguillen/chess/web/game/GameController.java` (new; `@Tag` + per-handler `@Operation` + full `@ApiResponse` matrix from initial pass; private static mapper `toMoveSummary` after closing iteration)
- `src/main/java/io/github/dariogguillen/chess/web/game/MoveRequest.java` (new; Jakarta-validated request record)
- `src/main/java/io/github/dariogguillen/chess/web/game/GameStateResponse.java` (new; unified response record; carries the nested `MoveSummary` record after closing iteration)
- `src/test/java/io/github/dariogguillen/chess/domain/GameTest.java` (modified: 2 new tests for the `startingFen` invariant, prior tests threaded with the new field)
- `src/test/java/io/github/dariogguillen/chess/web/game/GameControllerIT.java` (new; 7-test IT covering get-unknown, get-existing, Fool's Mate sequence, wrong-player, illegal-move, move-after-checkmate, missing-header)
- `docs/architecture.md` (modified: surgical update on lines 74-82 reflecting the `cache/` move)
- `README.md` (modified: added "Games" subsection with two curl examples)
- `notes/05-game-rest-api.md` (new; two "Gotchas" paragraphs dated 2026-05-19 added during closing iteration; File map fixed to reflect post-cleanup state)
- `feature_list.json` (modified: `game-rest-api.status` → `done`)

**Feature note:** `notes/05-game-rest-api.md`.

## 2026-05-19 — websocket-realtime

**Status:** done

**Summary:** First non-REST surface of the project. Added Spring
WebSocket + STOMP so that after a move is accepted via REST, a
`MoveEvent` is broadcast to all subscribers of
`/topic/games/{gameId}`. The endpoint lives at `/ws`; the in-process
`SimpleBroker` handles `/topic` destinations; the application prefix
`/app` is registered for future server-bound client messages but
unused today. Allowed origin patterns cover the production frontend
(`https://dariogguillen.github.io`) and any localhost port for dev.

The broadcast is triggered from `GameService.applyMove` **after**
the successful `gameStore.compute(...)` returns — outside the
lambda. If `SimpMessagingTemplate.convertAndSend` throws (broker
hiccup, serialization issue), the failure is caught and logged at
WARN with the gameId; the REST POST response is unaffected. The
mutation has already committed, and subscribers that missed an event
recover via reconnect + resync (a downstream concern handled by the
frontend in its feature 5). The cross-store invariant that already
existed (a `Game` exists iff its `Room` is `ACTIVE`, atomic per
`ConcurrentHashMap.compute`) is preserved unchanged; the broadcast
is a pure side channel.

`MoveEvent` is a `record` with 11 flat fields:
`(gameId, movedBy, side, from, to, promotion, fen, status, turn,
moveNumber, playedAt)`. The shape is deliberately **flat** rather
than nested (no shared `MoveDto` with the `web/game/` package) so
the `websocket/` layer does not depend on the `web/` layer. The
`from`/`to`/`promotion` wire shape is identical to the REST DTOs;
the Java type is independent. `movedBy` and `side` let the client
filter its own moves to avoid redundant local update after
submitting via REST. `playedAt` is derived from the injected
`Clock` so tests can pin time deterministically.

This is the **first cross-contract feature** under the discipline
added on 2026-05-19 to `AGENTS.md` and `.claude/agents/leader.md`.
The canonical doc for the STOMP surface is `docs/architecture.md`'s
new section "STOMP API contract"; it documents the endpoint URL,
broker choice and scale-out constraint, allowed origins, the
`/topic/games/{gameId}` subscription, the full `MoveEvent` JSON
example with every field annotated, the no-STOMP-auth design choice
(spectator-mode in feature 6.5 relies on it), the failure mode,
ordering/concurrency guarantees, and an explicit cross-repo note
naming `chess-frontend`'s feature 5 (`stomp-live-updates`) as the
mirror target. The frontend can copy the section verbatim when it
gets there.

`GameWebSocketIT` (5 tests) validates the contract end-to-end with
a real Spring context, a real WebSocket client, real STOMP frames,
and asserts on every field of the received `MoveEvent`:

1. `singleSubscriber_receivesMoveEvent_afterSuccessfulMove` —
   happy path, every field validated.
2. `twoSubscribers_bothReceiveMoveEvent` — two subscribers on the
   same topic both receive identical events.
3. `subscribingToOtherGame_doesNotReceiveBroadcast` — topic isolation.
4. `illegalMove_doesNotBroadcast` — 422 ILLEGAL_MOVE does not
   trigger a broadcast.
5. `moveByWrongPlayer_doesNotBroadcast` — 422 NOT_YOUR_TURN does
   not trigger a broadcast.

Five implementer-extra decisions landed and were validated by the
reviewer:

1. **`wscat` snippet in README**: a paste-and-run STOMP-frame
   example replaced the originally-considered JS snippet. More
   universal across tooling.
2. **Subscription registration delay = 1 second** in the IT (vs
   the brief's 200ms suggestion). The implementer started at 200ms,
   hit a different failure caused by a missing `JavaTimeModule` on
   the client-side message converter, fixed the converter, and
   kept 1s as a CI safety margin. Test suite still runs in ~16s.
3. **Helper structure in the IT**: `setupGame(white, black)`
   returns a `GameSetup(gameId, whitePlayerId, blackPlayerId)`
   record; `connect()` and `subscribe(session, gameId)` helpers
   factored out. All 5 tests route through them.
4. **`RestTemplate` 4xx handling**: wrapped in `try/catch
   (HttpStatusCodeException)` and reconstructed a `ResponseEntity`
   for uniform assertions across 2xx and 4xx paths.
5. **`JavaTimeModule` registered on the client-side
   `MappingJackson2MessageConverter`** in tests. Critical: without
   it, `MoveEvent.playedAt` silently fails to deserialize and the
   tests time out with no exception. The production side is fine
   because Spring Boot's autoconfigured `ObjectMapper` already has
   the module registered.

Out-of-scope observations (flagged for future awareness, not
blocking): the production message converter inherits Boot's
autoconfigured `ObjectMapper`; a future change that replaces it
explicitly must remember to register `JavaTimeModule`. The IT
shares one `WebSocketStompClient` across two sessions in the
two-subscribers test, which works but multiplexes on a single task
scheduler. Spotless re-flowed JavaDoc paragraphs in three files —
behaviour unchanged.

Manual end-to-end testing with a STOMP client (Insomnia, wscat,
`@stomp/stompjs` script) was discussed and intentionally **deferred
to the frontend's feature 5** (`stomp-live-updates`). That feature
will exercise the contract in a browser with the real client
library and over real CORS, which the IT cannot cover from inside
the JVM.

**Final test counts:** surefire **82** (unchanged from feature 5
close). Failsafe **25** (`HealthControllerIT` 3, `RoomControllerIT`
7, `OpenApiIT` 3, `GameControllerIT` 7, `GameWebSocketIT` 5).
Grand total **107** (was 102; delta +5).

**Files touched:**

- `src/main/java/io/github/dariogguillen/chess/config/WebSocketConfig.java` (new)
- `src/main/java/io/github/dariogguillen/chess/websocket/MoveEvent.java` (new)
- `src/main/java/io/github/dariogguillen/chess/service/GameService.java` (modified: +2 deps injected, +broadcastMoveEvent helper called after compute, try/catch with WARN-and-continue)
- `src/test/java/io/github/dariogguillen/chess/websocket/GameWebSocketIT.java` (new, 5 tests)
- `docs/architecture.md` (modified: new "STOMP API contract" section)
- `README.md` (modified: new "WebSocket (STOMP)" subsection with wscat example)
- `notes/06-websocket-realtime.md` (new)
- `feature_list.json` (modified: `websocket-realtime.status` → `done`)

**Feature note:** `notes/06-websocket-realtime.md`.

## 2026-05-19 — spectator-mode

**Status:** done

**Summary:** Added live viewer count to the STOMP surface introduced
in feature 6. The chosen approach is the simplest workable: listen to
Spring's STOMP session events (`SessionSubscribeEvent`,
`SessionUnsubscribeEvent`, `SessionDisconnectEvent`), maintain
in-memory per-game viewer sets in a new `@Component`
`ViewerCountTracker`, and broadcast `ViewerCountEvent` to a new
dedicated topic `/topic/games/{gameId}/viewers` on every count
change. The previously-pending choice between domain-modeled viewers
and anonymous-subscriber tracking was resolved in favor of the
latter — no new endpoints, no new domain types, no auth surface.

Player exclusion uses a STOMP header convention: clients that are
players of a game include `playerId:<uuid>` as a native header on
their SUBSCRIBE frame to the game topic. The tracker calls
`gameService.findById(gameId)` and compares against
`white.id()`/`black.id()`; on match, the subscriber is excluded from
the count entirely (no `subscriptionToGame` write, no broadcast).
The trust model is "trust at face value" — there is no auth in this
project — and the trade-off is documented in `docs/architecture.md`
as a portfolio-level choice. A future auth feature would replace
"trust" with "verify" without touching the tracker structure.

The tracker keeps three `ConcurrentHashMap` data structures:
`sessionsByGame: Map<gameId, Set<sessionId>>` (the actual viewer
set, dedupes by session so multiple subscriptions of the same
session count as one viewer), `gamesBySession: Map<sessionId,
Set<gameId>>` (for disconnect cleanup), and `subscriptionToGame:
Map<subscriptionId, GameSubscription>` where `GameSubscription` is
a private nested record `(sessionId, gameId)`. The third map is
necessary because `SessionUnsubscribeEvent` does not carry the
destination — only the subscription id; without recording the
mapping on subscribe, the unsubscribe handler cannot determine
which game to decrement.

The topic regex `^/topic/games/([^/]+)$` is end-anchored. Without
the `$`, the prefix matches `/topic/games/{gameId}/viewers` and the
tracker would count subscribers-of-the-count as if they were
subscribers-of-the-game — doubling the count. End-anchoring excludes
the `/viewers` sub-topic explicitly, which lets clients subscribe to
the count broadcast without affecting the count itself.

Six implementer-extra decisions landed and were validated:

1. **`playerId` header on the SUBSCRIBE frame, not CONNECT.**
   `SessionSubscribeEvent` carries the SUBSCRIBE frame's native
   headers; placing the header on CONNECT would require a different
   lookup path via session attributes populated from
   `SessionConnectEvent`. `docs/architecture.md` commits explicitly
   to SUBSCRIBE; the example STOMP frame in the doc is a literal
   SUBSCRIBE block.
2. **Defensive null guards** on `StompHeaderAccessor.getDestination()`,
   `getSessionId()`, and `getSubscriptionId()`. Spring's API returns
   `@Nullable`; these should never be null on real STOMP frames but
   the guards cost one line each.
3. **End-anchored regex** (`$` at the end), documented above and in
   the feature note's "Gotchas" section.
4. **Helper duplication** between `GameWebSocketIT` and
   `ViewerCountIT` (no shared `testsupport/` extraction). Rationale:
   two callers is the cutoff where extraction pays off, the
   helpers are small, and the two ITs may diverge further (this one
   needs `subscribeGame(playerId)` with custom headers). Decision
   captured in the note.
5. **Per-handler 1-second subscribe delay** (`SUBSCRIBE_REGISTRATION_DELAY_MS`)
   matching feature 6's pattern. The ~27s total IT runtime is
   dominated by these sleeps; tightening is documented as a
   tighten-later candidate.
6. **Broadcast completely skipped when the subscriber is a player.**
   The early `return` happens before any map write and before the
   broadcast call. The IT `playerSubscribes_countStaysAtZero_*`
   asserts no event reaches the player's viewer queue within 500ms,
   and that a subsequent non-player subscribe produces `count: 1`
   (not 2) — proving the player was excluded from the state, not
   just from the broadcast.

The cross-repo contract documentation extends (rather than rewrites)
the "STOMP API contract" section of `docs/architecture.md` added in
feature 6. Two new subsections — "Viewer count broadcasts" and
"`playerId` header convention" — sit at the end of that section,
followed by the existing cross-repo coordination note (updated to
mention both the new topic and the new header convention for
`chess-frontend`'s upcoming feature 5).

Five new tests in `ViewerCountIT` cover the contract end-to-end:
non-player subscribes ticks to one; player subscribes stays at zero
and excluding logic verified by a subsequent non-player joining and
seeing count one not two; two non-player subscribers tick to two;
disconnect ticks down; explicit UNSUBSCRIBE (without disconnect)
also ticks down — the last test specifically exercises the
`subscriptionToGame` cleanup path separately from disconnect.

Manual end-to-end testing with a real STOMP client is deferred to
`chess-frontend`'s feature 5 (`stomp-live-updates`), where the
actual `@stomp/stompjs` library and real CORS handshake will
exercise the contract in a browser.

**Final test counts:** surefire **82** (unchanged from feature 6).
Failsafe **30** (`HealthControllerIT` 3, `RoomControllerIT` 7,
`OpenApiIT` 3, `GameControllerIT` 7, `GameWebSocketIT` 5,
`ViewerCountIT` 5). Grand total **112** (was 107; delta +5).

**Files touched:**

- `src/main/java/io/github/dariogguillen/chess/websocket/ViewerCountEvent.java` (new; record `(gameId, count)`)
- `src/main/java/io/github/dariogguillen/chess/websocket/ViewerCountTracker.java` (new; `@Component` with three `@EventListener` handlers)
- `src/test/java/io/github/dariogguillen/chess/websocket/ViewerCountIT.java` (new, 5 tests)
- `docs/architecture.md` (modified: "STOMP API contract" section extended with "Viewer count broadcasts" + "`playerId` header convention" subsections; cross-repo coordination note updated)
- `README.md` (modified: "WebSocket (STOMP)" subsection extended with viewers topic + header convention bullets)
- `notes/06.5-spectator-mode.md` (new; filename uses `06.5-` decimal verbatim)
- `feature_list.json` (modified: `spectator-mode.status` → `done`)

**Feature note:** `notes/06.5-spectator-mode.md`.

## 2026-05-20 — constrain-error-codes

**Status:** done

**Summary:** Cross-repo-driven feature, inserted at priority 6.6
between `spectator-mode` and the devops block. Trigger:
`chess-frontend` feature 3 (`rest-room-integration`) is about to
consume the backend's OpenAPI spec via `openapi-typescript` codegen +
`openapi-fetch` typed client. As the spec stood, `ErrorResponse.error`
was an unconstrained `string` field; the frontend would have received
`error: string` and had to either maintain a parallel TS union by hand
(drift risk) or do runtime string matching with no compiler help.

The fix is a single annotation on the field: `@Schema(allowableValues
= {ROOM_NOT_FOUND, ROOM_FULL, GAME_NOT_FOUND, GAME_ALREADY_ENDED,
ILLEGAL_MOVE, NOT_YOUR_TURN, VALIDATION_FAILED, MALFORMED_REQUEST,
MISSING_HEADER})` enumerating the 9 codes that `GlobalExceptionHandler`
actually emits today. Springdoc renders this as an `enum` array in the
OpenAPI spec; `openapi-typescript` turns it into a TypeScript union
literal automatically. The frontend gets compile-time narrowing for
free without maintaining the list.

A handler audit was performed by both the implementer and the
reviewer independently. The 9 codes break down as: **6 from
mechanical `codeOf` derivation** on typed exceptions
(`RoomNotFoundException`, `RoomFullException`, `GameNotFoundException`,
`GameAlreadyEndedException`, `IllegalMoveException`,
`NotYourTurnException`) and **3 hardcoded literals** in the
framework-exception handlers (`VALIDATION_FAILED` for
`MethodArgumentNotValidException`, `MALFORMED_REQUEST` for
`HttpMessageNotReadableException`, `MISSING_HEADER` for
`MissingRequestHeaderException` — these have hardcoded literals
because Spring's own exceptions do not follow our naming convention).
Both surfaces (handler + annotation) cross-checked: no orphans, no
missing codes, no 10th code lurking. `ROOM_ALREADY_STARTED` was
explicitly excluded — it existed transiently in feature 4 but was
removed in its late-addition #4 cleanup.

The drift canary is a new IT in `OpenApiIT`:
`errorResponseSchema_listsExactlyTheNineKnownErrorCodes`. It pulls
`/v3/api-docs` via `MockMvc`, walks
`components.schemas.ErrorResponse.properties.error.enum`, sort-then-
compares against the expected 9. The test fails if the enum is
missing (springdoc dropped the annotation), if any code is added
without updating the annotation, or if any code is removed. Sort-
then-compare makes the test independent of source ordering of the
`allowableValues` array.

The cross-repo doc was extended: `docs/architecture.md`'s existing
"API contract" section gained a `#### Error codes` subsection
(heading depth confirmed correct against the existing hierarchy:
`## Layered architecture` > `### API contract` > `#### Error codes`)
with a table mapping each of the 9 codes to its HTTP status and
originating exception. The frontend's own `docs/architecture.md` can
mirror this table when it reaches feature 3.

Three implementer-extra decisions, all validated by the reviewer:

1. **Spotless reflowed** the `StreamSupport.stream(...).map(...).
   sorted().toList()` chain in the new test from multi-line (as the
   plan showed) to a single line. Stays under the 120-column hard
   limit; reads cleanly.
2. **`####` heading depth** for "Error codes" in
   `docs/architecture.md` rather than `###`. Correct because "API
   contract" is at `###` under `## Layered architecture`; "Error
   codes" as a child of "API contract" preserves the hierarchy.
3. **Plan-wording adopted verbatim** for the field description
   ("Stable upper-snake-case error code identifying the error class.
   Intended for programmatic matching by clients."). More
   informative than the pre-existing shorter text, brief explicitly
   allowed.

**Final test counts:** surefire **82** (unchanged), failsafe was 30
→ now **31** (+1 in `OpenApiIT`). Grand total **113** (was 112; delta
+1).

**Files touched:**

- `src/main/java/io/github/dariogguillen/chess/exception/ErrorResponse.java` (modified: added `allowableValues`, expanded `description`, kept `example` on the field-level `@Schema`)
- `src/test/java/io/github/dariogguillen/chess/config/OpenApiIT.java` (modified: added drift-canary test `errorResponseSchema_listsExactlyTheNineKnownErrorCodes`)
- `docs/architecture.md` (modified: added `#### Error codes` subsection under `### API contract` with table + drift-canary note)
- `notes/06.6-constrain-error-codes.md` (new; filename uses decimal verbatim per convention)
- `feature_list.json` (modified: `constrain-error-codes.status` → `done`; also: `backend-containerize.status` → `in_progress` so the parked plan can resume)

**Feature note:** `notes/06.6-constrain-error-codes.md`.

Process note: the plan for `backend-containerize` (priority 7) was
parked at `progress/parked-backend-containerize.md` while this
feature shipped, and restored to `progress/current.md` at close. The
leader did not re-discuss the containerize plan with the user before
restoring — it was approved verbatim before the cross-repo trigger
appeared, and the parking was a pause, not a withdrawal.

## 2026-05-20 — backend-containerize

**Status:** done

**Summary:** First of three sub-features (7, 7.5, 7.7) that take the
backend from "runs locally with Testcontainers" to "deployable on AWS
Free Tier with CI/CD". This one ships the containerization layer: a
multi-stage `Dockerfile`, a local-stack `docker-compose.yml`, and an
`application.yml` upgrade so the same artifact runs under three
contexts (Testcontainers, docker-compose, production-pending).

The Dockerfile uses `eclipse-temurin:21-jdk-jammy` for the builder
stage and `eclipse-temurin:21-jre-jammy` for the runtime — JRE-only
keeps the image around the ~200MB mark without the Alpine glibc quirks
or distroless's lack-of-shell tradeoff. `dependency:go-offline` runs
before `COPY src` so the Maven dependency layer caches independently
of code changes. `HEALTHCHECK` hits `/api/health` with a 60s start
period (Spring Boot warmup budget). The `chess-*.jar` glob in the
`COPY --from=builder` line deliberately matches only the repackaged
fat jar, not the `.jar.original` sibling that `spring-boot:repackage`
also produces.

`docker-compose.yml` is Compose v2 (no `version:` field), bringing up
Postgres 16, Redis 7-alpine, and the containerized app with
`depends_on: service_healthy` so the app waits for actual readiness,
not just process-up. The app service has `build: .` and no explicit
`image:` field — feature 7.7 (`backend-cicd-pipeline`) will own the
canonical tag (likely ECR) and adding one now would either pre-decide
or get clobbered. Host port mappings (5432, 6379, 8080) let tools on
the host reach each service.

`application.yml` upgraded from a single-line `spring.application.name`
to a full env-var-with-default block for `spring.datasource.*` and
`spring.data.redis.*`. Critical: this is purely additive. The
`TestcontainersConfiguration` uses `@ServiceConnection` which
overrides these properties at test runtime, so the Testcontainers
pipeline keeps working unchanged. The defaults (`localhost:5432`,
`localhost:6379`) match the hybrid workflow (compose for infra +
`./mvnw spring-boot:run` for the app). docker-compose sets the env
vars explicitly to in-network hostnames (`postgres`, `redis`).

README's "Running locally" section was rewritten with three
sub-sections covering the three workflows (Testcontainers,
docker-compose, hybrid) plus a closing paragraph on the env-var
pattern. `docs/architecture.md` got a new "Deployment artifact"
subsection under "Layered architecture" — 4 sentences covering
container as the artifact, multi-stage build, env-var-with-default
pattern, three runtime contexts, and the explicit decision to keep
`init.sh` Docker-free (with a forward reference to feature 7.7 for
the CI Docker smoke test).

Four implementer-extra decisions, all validated by the reviewer:

1. **`curl` availability in `eclipse-temurin:21-jre-jammy`** verified
   empirically (`docker run --rm ... which curl` → present). No
   `apt-get install` fallback needed.
2. **Compose v2 syntax** (no `version:` field; `docker compose` not
   `docker-compose` in README).
3. **No `image:` field** on the `app` service in `docker-compose.yml`
   — feature 7.7 will own the canonical tag.
4. **`chess-*.jar` glob** in the runtime stage's `COPY --from=builder`
   line — matches the executable fat jar but not the `.jar.original`
   sibling.

A late-in-feature small follow-up was applied before close:
`TestcontainersConfiguration` was pinned from `postgres:latest` /
`redis:latest` to `postgres:16` / `redis:7-alpine`. This makes the
test environment track the deployment environment — same Postgres and
Redis versions in tests and in docker-compose (and, by extension,
features 7.5/7.7 in production). Side effect: the Flyway WARN
("PostgreSQL 18.4 is newer than this version of Flyway and support
has not been tested") is gone because Flyway fully supports Postgres
16. The reviewer flagged this as out-of-scope drift risk; the user
chose to fold it into feature 7 rather than spin a separate
follow-up.

The reviewer also flagged two other lower-priority observations
left as future polish: `docker-compose.yml` does not pin a patch
version of `postgres:16` (floats within the 16 line, which is
typically desirable) and the Dockerfile does not use BuildKit cache
mounts for the Maven local repo (would shave time off rebuilds but
hide the layering — the layer-cache approach was the right
"learning-material" version).

The frontend is unaffected by this feature; containerization is pure
internal infrastructure.

**Final test counts:** surefire **82** (unchanged), failsafe **31**
(unchanged), grand total **113**. Test pipeline now runs against
`postgres:16` and `redis:7-alpine` (matching deployment) instead of
`postgres:latest` and `redis:latest`.

**Files touched:**

- `Dockerfile` (new; multi-stage JDK→JRE, healthcheck, repackaged-jar glob)
- `.dockerignore` (new; excludes target/, .git/, harness directories, IDE files)
- `docker-compose.yml` (new; app + Postgres 16 + Redis 7-alpine with health-aware depends_on)
- `src/main/resources/application.yml` (modified: extended from one-line to env-var-with-default for datasource and Redis)
- `src/test/java/io/github/dariogguillen/chess/TestcontainersConfiguration.java` (modified: pinned to `postgres:16` and `redis:7-alpine` to match docker-compose; late-in-feature follow-up)
- `README.md` (modified: rewrote "Running locally" with three workflows + closing paragraph on env-var pattern)
- `docs/architecture.md` (modified: added "Deployment artifact" subsection under "Layered architecture")
- `notes/07-backend-containerize.md` (new; filename uses `07-` zero-padded)
- `feature_list.json` (modified: `backend-containerize.status` → `done`)

**Feature note:** `notes/07-backend-containerize.md`.

## 2026-05-20 — backend-aws-infra

**Status:** done

**Summary:** Provisioned the AWS Free Tier production stack
declaratively with Terraform: EC2 t3.micro running Ubuntu 24.04 in
us-east-2, RDS Postgres 16 db.t3.micro single-AZ, Elastic IP, ECR
repository (empty — feature 7.7 will populate), AWS Budget alarm at
$1/month with email subscriber. The EC2 instance is configured by
cloud-init at first boot: installs Docker via Docker's official apt
repo, installs Caddy via Cloudsmith repo, creates a `deploy` user in
the `docker` group (no sudo), writes the Caddyfile from a Terraform
`templatefile()`, enables `docker` + `caddy` systemd services. Caddy
terminates TLS for `chess-backend.duckdns.org` via Let's Encrypt
(HTTP-01 challenge — requires port 80 open) and reverse-proxies to
the backend container on `localhost:8080`. A new
`docker-compose.prod.yml` (sibling to the local-dev compose from
feature 7) declares only Redis 7-alpine self-hosted + the app
container; Postgres comes from RDS via env vars. The app binds
`127.0.0.1:8080:8080` — loopback only, so Caddy is the only way in
from the internet. The user runs the deploy hands-on by following
`docs/deploy-runbook.md` (10 steps from `terraform apply` to
`curl https://chess-backend.duckdns.org/api/health → 200`); feature
7.7 automates this via GitHub Actions OIDC + ECR push +
SSH-triggered restart.

**Decisions captured in the note:**

1. **Terraform over manual console**: declarative IaC + reproducibility
   beats imperative scripts; `destroy + apply` regenerates the whole
   stack at any time, useful for re-platforming when AWS credits run
   out.
2. **Caddy over Nginx**: 3-line Caddyfile + automatic Let's Encrypt
   renewal vs Nginx + Certbot + ~30-line config + cron renewal.
   Modern alternative with growing adoption.
3. **Default VPC**: skips one layer of Terraform complexity. Creating a
   custom VPC is a learning exercise on its own; not needed for a
   single-instance backend.
4. **RDS for Postgres, self-hosted Redis in Docker**: ElastiCache isn't
   in AWS free tier ($13/mo minimum). Self-hosting Redis on the EC2
   saves ~$15/mo and is fine for our scale (Redis state is ephemeral
   here — no persistence concern).
5. **Manual first deploy, then automate**: understanding what feature
   7.7's CI/CD will automate is much easier after doing it by hand
   once. The runbook is the spec for the automation.
6. **Single-AZ RDS**: Multi-AZ doubles the cost and isn't in free tier.
   Single-AZ is appropriate for portfolio scope.
7. **`127.0.0.1:8080:8080` binding**: container's port 8080 is reachable
   only from the EC2 host itself (where Caddy lives). Internet can't
   bypass Caddy → HTTPS is enforced.
8. **AMI filter `architecture=x86_64`** (caught by the implementer
   beyond the plan): without this, the AMI lookup can resolve to an
   arm64 image, which fails to launch on `t3.micro`.
9. **`lifecycle { ignore_changes = [user_data] }` on EC2** (also caught
   beyond the plan): cloud-init runs once on first boot; future edits
   to the cloud-init template should NOT force-replace the running
   instance.
10. **RDS `max_allocated_storage = allocated`** (= 20 GB): disables
    storage autoscaling so the database stays pinned to the free-tier
    ceiling.
11. **Default tags via provider block** (`Project`, `ManagedBy`,
    `Owner`): cost allocation + cleanup hygiene.
12. **`.terraform.lock.hcl` committed**: Terraform recommends committing
    the lockfile for reproducible provider versions across machines
    and CI.

**Post-review amendments (folded into the same feature):**

The first reviewer pass returned REJECTED with one in-scope blocker
and one out-of-scope observation. The user chose to fold both into
feature 7.5 rather than spinning a separate follow-up:

1. **Runbook reproducibility**: the runbook used `bzip2`/`bunzip2` for
   the `docker save | ssh` transfer, but Ubuntu 24.04 cloud AMI does
   not ship `bzip2` preinstalled. Switched to `gzip`/`gunzip` (both
   ends) and added a one-sentence rationale in the runbook so a
   future reader (or future-you) understands the choice.
2. **Springdoc HTTP-vs-HTTPS**: behind Caddy, Springdoc was reporting
   `servers[0].url = "http://..."` because it inspects the inner
   `localhost:8080` request. Swagger UI loaded over HTTPS would then
   block `Try it out` requests as mixed content. Fix: added
   `server.forward-headers-strategy: framework` to `application.yml`
   so Spring honors Caddy's `X-Forwarded-Proto: https` /
   `X-Forwarded-Host` headers. Local-dev unaffected (no proxy = no
   header = standard request URL). Covered by a new IT
   `OpenApiForwardedHeadersIT` with two assertions: positive (with
   `X-Forwarded-Proto: https` → URL starts with `https://`) and drift
   canary (without the header → URL starts with `http://`, proving
   the strategy is actually consulting the header).

**Live deployment evidence (captured at close):**

- `terraform apply` succeeded in ~7 min, RDS provisioning ~6:28 of
  that.
- EC2 EIP: `18.189.228.186`, attached to the instance, never
  detached (free while associated).
- Duck DNS subdomain `chess-backend.duckdns.org` points at the EIP;
  DNS propagation < 1 min.
- Caddy obtained Let's Encrypt cert via HTTP-01 within seconds; cert
  visible in `journalctl -u caddy`. Internet scanners (leakix.net,
  etc.) found the domain within minutes via Certificate Transparency
  logs — noisy but expected for any public HTTPS endpoint.
- `cloud-init status --wait` → `done` after ~3 min from boot.
- Docker 29.5.2 and Caddy 2.11.3 installed by cloud-init.
- Container deploy via `docker save | gzip | ssh | gunzip | sudo
  docker load` (image ~365 MB content, ~90 MB on the wire), then
  `scp` of `.env` and `docker-compose.prod.yml` to `/opt/chess/`,
  then `docker compose -f docker-compose.prod.yml up -d` as the
  `deploy` user.
- Both containers `(healthy)`; Spring Boot 3.5.14 started in 13.7s;
  Hikari connected to RDS Postgres 16.13; Flyway clean ("Schema
  `public` is up to date. No migration necessary"); Hibernate ORM
  6.6.49 up; SimpleBrokerMessageHandler started (WebSocket/STOMP
  from feature 6); Tomcat on 8080.
- `curl -sS -i https://chess-backend.duckdns.org/api/health`
  returns `HTTP/2 200` with JSON
  `{"status":"UP","version":"0.0.1-SNAPSHOT","timestamp":"..."}`.
  Caddy advertises HTTP/3 via `alt-svc: h3=":443"`.
- Swagger UI renders at
  `https://chess-backend.duckdns.org/swagger-ui/index.html` showing
  the OpenAPI 3.1 spec from feature 4.5 + endpoints from features 5
  and 6.

**Caveat at close (user-acknowledged):**

The live deployment is still running the OLD image (without the
`forward-headers-strategy` fix). The user opted to wait for feature
7.7's CI/CD to push the new image rather than redeploying by hand.
The IT covers the regression so the next deploy (manual or
automated) will produce a Swagger UI that works `Try it out` end to
end. `/api/health` and core endpoints are unaffected.

**Final test counts:** surefire **82** (unchanged), failsafe **33**
(+2 from `OpenApiForwardedHeadersIT`), grand total **115**.

**Files touched:**

- `infra/main.tf` (new; 11 resources + 3 data sources, lifecycle ignore on user_data, lookup-by-filters for AMI)
- `infra/variables.tf` (new; aws_region default us-east-2, db_password sensitive, ssh + deploy keys, allowed_ssh_cidr, duckdns_subdomain default "chess-backend")
- `infra/outputs.tf` (new; ec2_elastic_ip, rds_address, rds_endpoint, ecr_repo_url, ssh_command, deploy_ssh_command, duckdns_fqdn)
- `infra/versions.tf` (new; Terraform >= 1.6, AWS provider `~> 5.70`)
- `infra/terraform.tfvars.example` (new; skeleton with all required variables, defaults that point at the user's existing setup)
- `infra/.gitignore` (new; *.tfstate*, .terraform/, terraform.tfvars)
- `infra/.terraform.lock.hcl` (new; committed per Terraform convention for reproducible provider versions)
- `infra/Caddyfile.tpl` (new; 3-line reverse_proxy + encode gzip + JSON file log)
- `infra/ec2-cloud-init.sh.tpl` (new; apt, Docker official repo, Caddy Cloudsmith repo, deploy user setup, Caddyfile from template, systemd enable)
- `docker-compose.prod.yml` (new; production compose — no Postgres, Redis self-hosted, 127.0.0.1:8080 loopback binding, env vars from /opt/chess/.env)
- `docs/deploy-runbook.md` (new; prerequisites + 10-step procedure + troubleshooting; uses `gzip`/`gunzip` post-amendment)
- `notes/07.5-backend-aws-infra.md` (new; ~19 KB feature note with all the DevOps concepts + post-review amendments section)
- `src/main/resources/application.yml` (modified: added `server.forward-headers-strategy: framework` block with inline rationale; post-amendment)
- `src/test/java/io/github/dariogguillen/chess/config/OpenApiForwardedHeadersIT.java` (new; 2 IT tests, positive + drift canary; post-amendment)
- `README.md` (modified: added `## Deployment` section pointing at the runbook + live URL)
- `docs/architecture.md` (modified: extended "Deployment artifact" subsection with the AWS production environment paragraph)
- `.gitignore` (modified: defensive entries for `infra/terraform.tfvars`, `infra/.terraform/`, `infra/*.tfstate*`)
- `feature_list.json` (modified: us-east-1 → us-east-2, Ubuntu 22.04 → Ubuntu 24.04, added acceptance bullet for the `forward-headers-strategy` regression IT, `backend-aws-infra.status` → `done`)

**Feature note:** `notes/07.5-backend-aws-infra.md`.

## 2026-05-20 — backend-cicd-pipeline

**Status:** done

**Summary:** Automated the production deploy. A push to `main` now
triggers `.github/workflows/deploy.yml` which runs `./init.sh`,
authenticates to AWS via **OIDC** (no static keys anywhere — the IAM
role is scoped by trust policy to
`repo:dariogguillen/chess-backend-java:ref:refs/heads/main`), builds
+ tags + pushes the Docker image to ECR (commit SHA + `latest`), SSHes
into EC2 as `deploy` with a dedicated CI key, runs
`docker compose pull && up -d` from `/opt/chess/`, and smoke-tests
`https://chess-backend.duckdns.org/api/health` until it returns 200.
Cleanly closes the trifecta 7 → 7.5 → 7.7 that took the backend from
"runs locally in Docker" to "production HTTPS deploys on every push to
main, fully reproducible from source".

**Terraform additions (6 resources + 1 in-place modify):**

1. `aws_iam_openid_connect_provider.github` — registers
   `token.actions.githubusercontent.com` as a trusted OIDC provider.
   Thumbprint `6938fd4d98bab03faadb97b34396831e3780aea1` hardcoded
   (modern AWS validates via JWKS, but the thumbprint is required to
   create the provider).
2. `aws_iam_role.github_actions` — assumable by the OIDC provider
   with a trust policy that requires both `aud = sts.amazonaws.com`
   and `sub LIKE "repo:dariogguillen/chess-backend-java:ref:refs/heads/main"`.
   Without the `sub` condition, ANY GitHub workflow could assume the
   role.
3. `aws_iam_role_policy_attachment` — attaches
   `AmazonEC2ContainerRegistryPowerUser` to the GHA role. Power-user is
   the conventional starting point; a future polish would tighten
   this to a least-privilege custom policy scoped to
   `arn:aws:ecr:us-east-2:546046686081:repository/chess-backend`.
4. `aws_iam_role.ec2_ecr_pull` — assumable by `ec2.amazonaws.com`.
5. `aws_iam_role_policy_attachment` — attaches
   `AmazonEC2ContainerRegistryReadOnly` (pull only, no push from EC2).
6. `aws_iam_instance_profile.ec2` — wraps the EC2 role.
7. `aws_instance.app` modified **in-place** to add
   `iam_instance_profile = aws_iam_instance_profile.ec2.name`. EC2
   stayed running during the `terraform apply` (~14s in-place modify,
   no replacement).

**Cloud-init template additions:**

- Replaced the (since removed by Ubuntu 24.04) `apt install awscli`
  with the **official AWS CLI v2 installer** pattern: `apt install -y
  unzip && curl -fsSL ... awscli-exe-linux-x86_64.zip -o /tmp/... &&
  unzip ... && /tmp/aws/install`. This affects future EC2 instances
  only; the user manually ran the same installer on the existing
  instance (documented in runbook section 11).

**Workflow shape (`.github/workflows/deploy.yml`):**

- Triggers: `push: branches: [main]` + `workflow_dispatch`.
- `concurrency: group: deploy-prod, cancel-in-progress: false` —
  queues rapid pushes instead of dropping them.
- `permissions: id-token: write, contents: read` — the single line
  that enables OIDC. Misconfiguring this is the #1 OIDC gotcha.
- 8 steps: Checkout → Set up Java 21 → Run init.sh → Configure AWS
  (OIDC) → Login to ECR → Build + tag + push → Deploy via SSH →
  Smoke test (12 retries × 5s = 60s cap).

**Manual one-time setup (documented in runbook sections 11/12/13):**

1. Install AWS CLI v2 on the existing EC2 via the official installer
   (cloud-init was retrofit for future instances).
2. Generate a dedicated CI SSH key (`~/.ssh/chess_ci`, ed25519, no
   passphrase) and append the pubkey to `/home/deploy/.ssh/authorized_keys`.
3. Set GitHub secrets `AWS_DEPLOY_ROLE_ARN` (Terraform output
   `github_actions_role_arn`) and `DEPLOY_SSH_KEY` (the chess_ci
   private key) via `gh secret set` from inside the repo dir.

**Decisions captured in the note:**

1. **OIDC over static IAM user keys**: short-lived STS credentials,
   no leakable secret, scoped to specific workflows by `sub` claim.
   The conventional pattern circa 2025+.
2. **`sub` claim restriction to one repo + branch**: critical —
   without it, any GitHub Actions workflow can assume the role.
3. **IAM instance profile vs IAM user keys on EC2**: EC2 picks up
   short-lived credentials via IMDSv2 silently when awscli runs. No
   `aws configure`, no static keys.
4. **`AmazonEC2ContainerRegistryPowerUser` on GHA role**: conventional
   starting point. Polish opportunity: replace with a custom
   least-privilege policy scoped to the chess-backend ECR repo ARN.
5. **`AmazonEC2ContainerRegistryReadOnly` on EC2 role**: strictly
   minimum. EC2 only pulls, never pushes.
6. **Dedicated CI SSH key, not reuse personal**: separation of
   concerns + rotation + audit + blast radius control.
7. **`docker compose pull && up -d` over `docker save | ssh | docker load`**:
   faster, registry-native, leverages the ECR repo provisioned in
   7.5. The manual `save | load` stays in the runbook as a fallback
   for emergency deploys without a working CI/CD.
8. **In-place IAM instance profile attach**: Terraform attribute-level
   diff. Verified `~` (modify) not `-/+` (replace) in the plan before
   applying. EC2 stayed running.
9. **Concurrency `cancel-in-progress: false`**: rapid main pushes
   queue instead of dropping. For a sparse-push portfolio, trivial;
   for high-traffic main branches it prevents the partial-push +
   never-restarted-container failure mode.
10. **Ubuntu 24.04 dropped the `awscli` apt package** (post-2024 LTS
    decision by Canonical): mandates the official v2 installer
    (curl + unzip + run installer). Affects both cloud-init (for
    future instances) and the manual one-time upgrade in the runbook.

**Live deployment evidence:**

- `terraform apply`: 6 resources created + 1 in-place modify in <30s.
- `aws sts get-caller-identity` from inside the EC2 returns
  `assumed-role/chess-backend-ec2-ecr-pull/i-0af4adeab6d7afb02` —
  proves instance profile + IMDSv2 working.
- First workflow run failed at "Deploy to EC2 over SSH" with
  `pull access denied for chess-backend` (see post-review amendments
  below).
- Second workflow run after the fix: **all 8 stages green in 4m 25s**.
- ECR repository now has 2 images visible (commit SHA tags + a
  rolling `latest`); lifecycle policy will keep the last 5.
- `curl https://chess-backend.duckdns.org/api/health` returns
  `HTTP/2 200 {"status":"UP"}`.
- `curl https://chess-backend.duckdns.org/v3/api-docs | jq '.servers'`
  now returns `https://chess-backend.duckdns.org` (NOT `http://`) —
  the `forward-headers-strategy: framework` fix from 7.5 reached
  production for the first time via this deploy.

**Post-review amendments (folded into the same feature):**

The reviewer's first walkthrough was clean, but a separate bug
discovered DURING the runbook execution was folded back into 7.7's
scope before close:

1. **Ubuntu 24.04 `awscli` apt package removal**: the original
   runbook + cloud-init template both said `apt install awscli`,
   which fails with "Package 'awscli' has no installation candidate"
   on the cloud AMI. Fixed by switching to the AWS CLI v2 official
   installer in both the cloud-init template and the runbook
   section 11.

2. **Docker Compose v5.1.4 colon-in-default parser bug**: the
   original `docker-compose.prod.yml` used
   `image: ${APP_IMAGE:-chess-backend:latest}` to allow CI/CD to
   override with the ECR URL+SHA while keeping the manual-deploy
   default. Compose v5.1.4 (which ships with Docker 29.5.2 on
   Ubuntu 24.04) silently fell back to the default `chess-backend:latest`
   regardless of whether APP_IMAGE was set inline, exported, or
   written to `.env`. SPRING_* variables in the same compose file
   substituted correctly — confirmed the bug is specific to the
   `:-default-with-colons` parser path. Fix: split into two
   variables without colons in defaults:
   `image: ${APP_IMAGE_REPO:-chess-backend}:${APP_IMAGE_TAG:-latest}`,
   with the workflow `export`ing both `APP_IMAGE_REPO` and
   `APP_IMAGE_TAG` before each `docker compose` call inside the
   SSH session. Documented in the note's "Gotchas" section.

3. **The fix doesn't sync the EC2's compose file**: `/opt/chess/docker-compose.prod.yml`
   is not currently scp'd by the workflow — only the image is
   updated via ECR. Surfaced as a polish opportunity (next deploy
   workflow improvement). For 7.7's close, the user manually
   scp'd the new compose to `/opt/chess/` after the implementer's
   fix; the running workflow then uses it correctly.

4. **Cross-feature: `feature_list.json` for `github-actions-ci`
   (priority 12) was re-scoped** to "PR-only validation workflow" —
   `./init.sh` on pull requests, Maven cache, status badge in
   README. The push-to-main path is fully covered by this feature's
   `deploy.yml`. The original 12 description spoke of "push and
   pull request"; now it's "pull request only".

**Caveat at close (user-acknowledged):**

The EC2's `/opt/chess/docker-compose.prod.yml` is now manually
synced (post the round-2 compose split). Future workflow runs will
correctly pull-and-restart, but if `docker-compose.prod.yml` in the
repo changes again, the user will need to scp it manually OR a
follow-up feature should add a `scp` step to the workflow. This is
documented in the note's "Gotchas" + flagged for a future polish.

**Final test counts:** surefire **82** (unchanged), failsafe **33**
(unchanged from 7.5 close — no new ITs in 7.7), grand total **115**.

**Files touched:**

- `.github/workflows/deploy.yml` (new; 8-stage deploy workflow with OIDC + ECR + SSH + smoke test)
- `infra/main.tf` (modified: +OIDC provider, +2 IAM roles, +2 policy attachments, +instance profile, modified aws_instance.app with iam_instance_profile)
- `infra/variables.tf` (modified: +github_repo, +github_branch)
- `infra/outputs.tf` (modified: +github_actions_role_arn)
- `infra/ec2-cloud-init.sh.tpl` (modified: removed apt awscli, added unzip + AWS CLI v2 official installer)
- `infra/terraform.tfvars.example` (modified: documented the two new optional variables)
- `docker-compose.prod.yml` (modified: split image var into APP_IMAGE_REPO + APP_IMAGE_TAG to dodge compose v5.1.4 colon-in-default bug)
- `docs/deploy-runbook.md` (modified: +sections 11/12/13 for one-time EC2 awscli install + CI SSH key + GH secrets + trigger/monitor a deploy; +CI/CD architecture diagram)
- `docs/architecture.md` (modified: +"Deploy automation" subsection at lines 112-136)
- `README.md` (modified: extended "Deployment" section with the CI/CD paragraph + link to deploy.yml)
- `notes/07.7-backend-cicd-pipeline.md` (new; ~474-line feature note covering OIDC, IMDSv2, sub claim restriction, IAM trade-offs, compose v5.1.4 gotcha, Ubuntu 24.04 awscli gotcha, dedicated CI key separation rationale, polish opportunities)
- `feature_list.json` (modified: deploy-backend.yml→deploy.yml in acceptance, re-scoped github-actions-ci priority 12 to PR-only validation, backend-cicd-pipeline.status → done)

**Feature note:** `notes/07.7-backend-cicd-pipeline.md`.

---

## 2026-05-21 — redis-active-state

Migrated the active state (Rooms and Games) from in-memory `ConcurrentHashMap`s to Redis with a 24h TTL, refreshed on every write. Pure storage swap behind the existing `RoomStore` / `GameStore` interfaces — no service, controller, or websocket file touched, no API contract change, no frontend coordination needed. The strongest regression signal was that every existing IT (`RoomControllerIT`, `GameControllerIT`, `GameWebSocketIT`, `ViewerCountIT`, `OpenApiIT`, `OpenApiForwardedHeadersIT`, `HealthControllerIT`) passed unchanged against the new Redis-backed stores.

**Approach decisions** (with rejected alternatives documented in the feature note):

1. **Two typed `RedisTemplate<String, T>` beans** (one for `Room`, one for `Game`) over a single `GenericJackson2JsonRedisSerializer`. Rationale: the domain has no polymorphism to preserve; per-type `Jackson2JsonRedisSerializer<T>` keeps `redis-cli GET room:abc` output free of `@class` metadata and the round-trip is type-safe at compile time.
2. **Spring Boot's autoconfigured `ObjectMapper` injected into `Jackson2JsonRedisSerializer`** rather than the single-arg constructor that builds an internal mapper. The single-arg variant would silently omit the `jackson-datatype-jdk8` module and serialize `Optional<Piece>` (on `Move.promotion`) as `{present, value}`, breaking the `Game` round-trip. Asserted by `RedisGameStoreIT.save_thenFindById_returnsEqualGame_includingMoveHistory`.
3. **Local per-key `ReentrantLock`s via `StripedKeyLock`** to preserve the `ConcurrentHashMap.compute(k, f)` semantics. Rejected: `WATCH/MULTI/EXEC` (Redis-idiomatic CAS, but adds a retry loop for zero benefit on a single-instance deploy) and Lua scripts (logic would leave Java's type system). The single-instance assumption and the upgrade path are explicit in `docs/architecture.md` and the feature note.
4. **TTL via `@ConfigurationProperties("chess.redis")` with a `Duration activeStateTtl`**, default `24h`, env override `CHESS_REDIS_ACTIVE_STATE_TTL`. Applied inside `save`; `compute` re-writes via `save` so refresh-on-activity is free. Reads never refresh.
5. **`ViewerCountTracker` (feature 6.5) stays in-memory** — explicitly out of scope. STOMP sessions are server-local; migrating viewer state to Redis would be misleading. Documented in the note and `architecture.md`.
6. **`RedisTemplate` directly, not `@RedisHash + CrudRepository`** — records don't fit the `@RedisHash` mutable-class model. The explicit template is more didactic and preserves the records-only convention.

**Reviewer rounds:** 1 → REJECTED on a single fully-qualified `new java.util.ArrayList<>(...)` in `RedisGameStoreIT.java:167` (missing import). 2 → APPROVED.

**Test totals:** 86 unit + 48 IT = 134 tests, all green, no skips. New tests: `StripedKeyLockTest` (4), `RedisRoomStoreIT` (6), `RedisGameStoreIT` (5), `RedisTtlIT` (4) = 19 new test cases.

**Files touched:**

Created:
- `src/main/java/io/github/dariogguillen/chess/cache/RedisRoomStore.java`
- `src/main/java/io/github/dariogguillen/chess/cache/RedisGameStore.java`
- `src/main/java/io/github/dariogguillen/chess/cache/StripedKeyLock.java`
- `src/main/java/io/github/dariogguillen/chess/config/RedisConfig.java`
- `src/main/java/io/github/dariogguillen/chess/config/RedisActiveStateProperties.java`
- `src/test/java/io/github/dariogguillen/chess/cache/StripedKeyLockTest.java`
- `src/test/java/io/github/dariogguillen/chess/cache/RedisRoomStoreIT.java`
- `src/test/java/io/github/dariogguillen/chess/cache/RedisGameStoreIT.java`
- `src/test/java/io/github/dariogguillen/chess/cache/RedisTtlIT.java`
- `notes/08-redis-active-state.md`

Modified:
- `src/main/resources/application.yml` (added `chess.redis.active-state-ttl: ${CHESS_REDIS_ACTIVE_STATE_TTL:24h}`)
- `docs/architecture.md` (new "Active state in Redis" subsection under "State strategy")
- `feature_list.json` (`redis-active-state.status` → `done`)

Deleted:
- `src/main/java/io/github/dariogguillen/chess/cache/InMemoryRoomStore.java`
- `src/main/java/io/github/dariogguillen/chess/cache/InMemoryGameStore.java`

**Deployment note:** the change is live in code and in the test suite, but not yet in production on EC2. Production deploys via `.github/workflows/deploy.yml` on push to `main`. The compose stack on EC2 already runs `redis:7-alpine` alongside the app (since feature 7.5), so no infra changes are needed — the next deploy will simply start using the new Redis-backed stores.

**Feature note:** `notes/08-redis-active-state.md`.

---

## 2026-05-22 — postgres-game-history

Persisted completed games (`CHECKMATE`, `STALEMATE`, `DRAW`, `ABANDONED`) to Postgres for permanent history. Redis (feature 8) stays as the source of truth for active games; Postgres is the archive layer — write-on-terminal, read-on-history-query. Two-day arc: round-1 was the implementation, round-2 was the type cleanup the user surfaced after reading the round-1 migration. The strongest regression signal is that every existing IT (Room, Game, GameWebSocket, ViewerCount, OpenApi, Redis*, Health) kept passing across both rounds without modification — the new archive path is guarded by `updated.status().isTerminal()` and the ones that DO reach terminal status (`GameControllerIT`'s Fool's Mate, extended in this feature) exercise the trigger transparently in a real REST flow.

**Round 1 — the relational + JPA scaffolding:**

1. **Two-table relational schema** (`games` + `moves`), not a `jsonb` moves column on a single table. Trade-off documented in the note: the `jsonb` shortcut is more Postgres-idiomatic but a relational `moves` table is what a portfolio-grade JPA feature should demonstrate and preserves queryability for future move stats.
2. **No `players` table** — denormalised player info on `games` (white/black `_player_id` + `_display_name`). Justification rewritten in round 2 to lead with **snapshot semantics**: display name at game time is the audit-correct value (same as Lichess, GitHub commits, Steam friend graphs). When auth lands later, a `V2__create_players.sql` extracts distinct UUIDs with `kind='HISTORICAL_GUEST'` and adds the FK — well-documented and obvious when needed.
3. **JPA entities mutable classes** with package-private setters (`GameEntity`, `MoveEntity`), composite key via `@IdClass(MoveEntityId.class)` with a Serializable record. Per project convention "DTOs are records, NEVER through controllers" → entities are NOT records.
4. **Synchronous archive inside `applyMove`** — the new `GameHistoryService.archive(Game)` call sits inside the existing `GameStore.compute(...)` lambda, BEFORE the Redis write. If Postgres throws, Redis does not advance — data integrity > UX.
5. **`@Enumerated(STRING)` + `ddl-auto: validate`** so the schema owned by Flyway is verified by the entity model at boot — silent drift refuses to start the app. `open-in-view: false` to disable the well-known Spring Boot anti-pattern default.
6. **JPQL constructor projection** (`SELECT new ArchivedGamePlayerView(... SIZE(g.moves) ...)`) for `findByPlayer` — needed because `open-in-view: false` would otherwise throw `LazyInitializationException` on the move count navigation. This was an implementer deviation from the original plan, justified because the plan itself mandated `open-in-view: false`.
7. **Hard cap of 50 newest, no pagination param** — out of scope; mentioned as polish for a future `player-stats` feature.
8. **`GET /api/players/{id}/games` returns 200 with `[]` for unknown player** — no 404, because guest players have no registry and an empty list is the honest answer.
9. **No `CHECK` constraint on the `status` column** — `@Enumerated(STRING)` already enforces from the only client writing this DB; a DB-side check would have to be touched on every future `GameStatus` addition.

Round-1 reviewer outcome: **APPROVED in round 1**, no rejections. 152 tests (91 unit + 61 IT, +17 new across `GameHistoryRepositoryIT`, `GameHistoryServiceIT`, `PlayerGamesControllerIT`, `GameEntityMapperTest` + 1 extension to `GameControllerIT`).

**User-surfaced issues before the OK and round 2:**

The user reviewed the migration and asked two questions: (a) why TEXT for almost every column when more specific types exist, and (b) whether a `players` table should be introduced anyway given the future auth direction. After discussion the agreed shape was:

1. **Full type cleanup**: `UUID` native columns for Player/Game ids; `VARCHAR(6)` for `room_id` (6-char short code from the custom alphabet `ABCDEFGHJKMNPQRSTUVWXYZ23456789`, NOT a UUID); bounded `VARCHAR(N)` everywhere else (display names 100, FENs 100, status 20, promotion 10); `VARCHAR(2)` for squares.
2. **No `players` table** — user agreed with the snapshot-semantics rationale. Documentation reinforced.
3. **No Postgres `ENUM` types** for `status`/`promotion` — user asked, I recommended against. Rationale documented: `@Enumerated(STRING)` already enforces from the only writer; Postgres ENUM adds migration churn on every value addition/rename; Hibernate→Postgres ENUM mapping needs `@JdbcTypeCode(SqlTypes.NAMED_ENUM)` or `AttributeConverter` boilerplate; `VARCHAR + @Enumerated(STRING)` is the idiomatic Spring/JPA pattern used by GitHub, Lichess, the canonical Spring guides.
4. **Move notation discussion**: user asked about ambiguous moves and whether `promotion` is needed. Answer documented: the `Move(from, to, promotion)` shape is Long Algebraic Notation (UCI/engines), unambiguous by construction because `from + to` defines the move uniquely (SAN is the ambiguous one with `Nbd2`-style disambiguation). Promotion is the only intra-move ambiguity LAN doesn't resolve (a pawn on e7 to e8 could promote to Q/R/B/N) and is therefore correctly modelled as `Optional<Piece>`. Check/checkmate are post-move state on the Game, not part of a Move.

**Round 2 — type cleanup and UUID end-to-end:**

The implementer's first attempt halted correctly: my plan said "keep `String` in domain + `@JdbcTypeCode(SqlTypes.UUID)` on the entity so Hibernate converts; mapper untouched". The implementer investigated and found that combination does NOT work at runtime — Hibernate has no built-in `String ↔ UUID` JDBC binding. Three variants all failed: `@JdbcTypeCode` alone (`HibernateException: Could not convert 'java.lang.String' to 'java.util.UUID'`); `@Convert(StringUuidConverter)` alone (`ERROR: operator does not exist: uuid = character varying` on `WHERE id = ?`); both together (the converter's binding is overridden by `@JdbcTypeCode`).

After user discussion, the correct path was clear: **`UUID` end-to-end** — DB column, JPA entity, AND domain record use `java.util.UUID` for Player.id and Game.id. The earlier "mapper untouched" goal was not load-bearing — the mapper-as-divergence-boundary pattern already exists for records-vs-entities, and applying it to types is the natural extension. `Room.id` and `Game.roomId` stay `String` because they are 6-char short codes, not UUIDs.

Cascade (the implementer caught a few items beyond the explicit prompt):

- Domain (`Player`, `Game`, `Room`) — `UUID` ids; `Room` uniqueness set becomes `Set<UUID>`; compact constructors drop the blank-id check (there is no blank concept for UUID).
- Entity (`GameEntity`, `MoveEntity`, `MoveEntityId`, `ArchivedGamePlayerView`, `GameHistoryRepository`) — plain `@Id UUID`, no `@JdbcTypeCode` trick; `GameHistoryRepository extends JpaRepository<GameEntity, UUID>`.
- Mapper — now **simpler** than round 1 (identity over UUID fields, no `.toString()` / `UUID.fromString` left).
- Services (`GameStore`, `RoomService`, `GameService`, `GameHistoryService`) — UUID parameters end-to-end; `RoomService` mints ids with `UUID.randomUUID()` (no `.toString()`).
- Cache (`RedisGameStore`) — key built with `id.toString()`; `StripedKeyLock<String>` API still receives a String; `Jackson2JsonRedisSerializer<Game>` serialises UUID to string in JSON unchanged.
- Exceptions (`GameNotFoundException`, `GameAlreadyEndedException`, `IllegalMoveException`, `NotYourTurnException`) — UUID parameters.
- `GlobalExceptionHandler` — **new handler** `MethodArgumentTypeMismatchException → 400 MALFORMED_REQUEST`, reusing the existing 9-code allowlist from feature 6.6 (no expansion). Two new ITs lock the behaviour: `PlayerGamesControllerIT.getPlayerGames_malformedUuid_returns400MalformedRequest` and `GameControllerIT.getGame_malformedUuid_returns400MalformedRequest`.
- Web (`GameController`, `PlayerGamesController`, `RoomResponse`, `GameStateResponse`, `PlayerGameSummary`) — `@PathVariable UUID`, `@RequestHeader UUID playerId`, DTO fields UUID. JSON wire format identical (Jackson UUID→string), Springdoc auto-adds `format: "uuid"` to the OpenAPI spec — no frontend coordination needed.
- WebSocket (`MoveEvent`, `ViewerCountEvent`, `ViewerCountTracker`) — UUID in wire types. `ViewerCountTracker` parses STOMP topic captures and the `playerId` header with a tolerant `parseUuidOrNull` helper (silently ignores malformed values — no auth, no error path to propagate). Documented at class- and method-javadoc level.
- Tests — `UUID.randomUUID()` / `UUID.fromString("00000000-...")` replacing literal strings throughout the test suite. One obsolete test removed (`GameTest.shouldReject_whenIdIsBlank` — blank concept no longer applies to UUID). Net delta: +2 new (malformed-UUID 400 ITs) − 1 removed = +1 test.

**`CHAR(2)` rejection** (documented in the migration header and the note): the implementer tried `CHAR(2)` for squares as planned, but `ddl-auto: validate` blocks boot with a Hibernate `Types.VARCHAR` vs Postgres `Types.CHAR` type-code mismatch. Hibernate maps Java `String` to `Types.VARCHAR` regardless of `columnDefinition`. Switched to `VARCHAR(2)` with an inline migration comment explaining why.

**`MoveEvent` / `ViewerCountEvent` cascade** (the implementer caught this beyond my explicit prompt): both wire-shaped websocket events carried `String gameId`/`movedBy`; migrated to UUID. Required the `parseUuidOrNull` helper in `ViewerCountTracker` for the STOMP topic regex capture and the `playerId` STOMP header.

**Bonus**: the implementer fixed a pre-existing fully-qualified `ResultActions` reference in `GameControllerIT` while editing that file, internalising the lesson from feature 8's round-1 rejection.

Round-2 reviewer outcome: **APPROVED in round 1 (of round 2)**, no rejections. Two out-of-scope observations: a duplicate "no `players` table" section in the note (residue from round 1, the rich version with snapshot-semantics + Lichess/GitHub/Steam led but the weaker copy hung around lower down); and `parseUuidOrNull` without a direct unit test (behaviour unobservable from outside, javadoc-documented, indirect coverage via positive paths in `ViewerCountIT`/`GameWebSocketIT`). User chose to polish the duplicate, accept the indirect coverage for `parseUuidOrNull`.

**Polish round** (two passes):

1. Deleted the duplicate "no `players` table" section in the note (lines 178-192 at the time), keeping the rich version that leads. No information lost — the V2 migration path was only in the rich version anyway.
2. Cleaned up a triple-blank-line residue around lines 162-164 and added the missing bold inline heading `**Separate games and moves tables, not a jsonb move history.**` to the orphaned decision so it matches the style of its neighbours. Bonus fix in the same pass: a broken inline code span on line 421 in a Scala parallel (unbalanced backtick mid-expression) repaired.

**Final test totals across the two rounds and the polish:** 90 unit + 63 IT = **153 tests**, all green, zero skips, zero failures, zero errors. `./init.sh` exits 0.

**Files touched across the arc:**

Created (round 1):
- `src/main/resources/db/migration/V1__create_game_history.sql`
- `src/main/java/io/github/dariogguillen/chess/persistence/GameEntity.java`
- `src/main/java/io/github/dariogguillen/chess/persistence/MoveEntity.java`
- `src/main/java/io/github/dariogguillen/chess/persistence/MoveEntityId.java`
- `src/main/java/io/github/dariogguillen/chess/persistence/GameHistoryRepository.java`
- `src/main/java/io/github/dariogguillen/chess/persistence/ArchivedGamePlayerView.java`
- `src/main/java/io/github/dariogguillen/chess/persistence/GameEntityMapper.java`
- `src/main/java/io/github/dariogguillen/chess/service/GameHistoryService.java`
- `src/main/java/io/github/dariogguillen/chess/web/game/PlayerGamesController.java`
- `src/main/java/io/github/dariogguillen/chess/web/game/PlayerGameSummary.java`
- `src/test/java/io/github/dariogguillen/chess/persistence/GameHistoryRepositoryIT.java`
- `src/test/java/io/github/dariogguillen/chess/persistence/GameEntityMapperTest.java`
- `src/test/java/io/github/dariogguillen/chess/service/GameHistoryServiceIT.java`
- `src/test/java/io/github/dariogguillen/chess/web/game/PlayerGamesControllerIT.java`
- `notes/09-postgres-game-history.md`

Modified across both rounds + polish:
- `src/main/resources/application.yml` (round 1: `spring.jpa.*`, `spring.flyway.*`)
- `src/main/java/io/github/dariogguillen/chess/service/GameService.java` (round 1: archive call; round 2: UUID parameters)
- `src/main/java/io/github/dariogguillen/chess/service/GameStore.java` (round 2: UUID parameters)
- `src/main/java/io/github/dariogguillen/chess/service/RoomService.java` (round 2: `UUID.randomUUID()` instead of `.toString()`)
- `src/main/java/io/github/dariogguillen/chess/domain/Player.java` (round 2: `id` → UUID)
- `src/main/java/io/github/dariogguillen/chess/domain/Game.java` (round 2: `id` → UUID)
- `src/main/java/io/github/dariogguillen/chess/domain/Room.java` (round 2: uniqueness set → `Set<UUID>`)
- `src/main/java/io/github/dariogguillen/chess/cache/RedisGameStore.java` (round 2: UUID id, key build)
- `src/main/java/io/github/dariogguillen/chess/config/RedisConfig.java` (round 2: javadoc clarification)
- `src/main/java/io/github/dariogguillen/chess/exception/GameNotFoundException.java`, `GameAlreadyEndedException.java`, `IllegalMoveException.java`, `NotYourTurnException.java`, `GlobalExceptionHandler.java` (round 2: UUID parameters; new MethodArgumentTypeMismatchException handler)
- `src/main/java/io/github/dariogguillen/chess/web/game/GameController.java`, `GameStateResponse.java`, `PlayerGameSummary.java`, `web/room/RoomResponse.java` (round 2: UUID wire types; 400 annotations)
- `src/main/java/io/github/dariogguillen/chess/websocket/MoveEvent.java`, `ViewerCountEvent.java`, `ViewerCountTracker.java` (round 2: UUID wire types + `parseUuidOrNull`)
- All persistence/service/web/cache/websocket tests touched by the UUID cascade
- `src/test/java/io/github/dariogguillen/chess/web/game/GameControllerIT.java` (round 1: post-mate Postgres assertion; round 2: UUID + new 400 IT + preexisting FQN fix)
- `docs/architecture.md` (round 1: new "Game history in Postgres" subsection; round 2: UUID end-to-end, bounded VARCHAR, no players table snapshot rationale, no Postgres ENUM, LAN-not-SAN)
- `notes/09-postgres-game-history.md` (round 1: initial content; round 2: UUID end-to-end + reinforced sections + Scala parallels; polish 1: duplicate removed; polish 2: bold heading + blank-line cleanup + inline-code repair)

Deleted: none (one obsolete test removed in round 2: `GameTest.shouldReject_whenIdIsBlank`).

**Deployment note:** the change is live in code and tests but not yet in production on EC2. Production deploys via `.github/workflows/deploy.yml` on push to `main`. RDS is private to the EC2 SG; Flyway will apply `V1__create_game_history.sql` automatically on first boot of the new container. If the migration fails in prod, `ddl-auto: validate` refuses to start the app and the workflow's smoke test catches it.

**Feature note:** `notes/09-postgres-game-history.md`.

---

## 2026-05-22 — room-lifecycle-events

Inserted as a new feature at priority 9.5 between `postgres-game-history` (9) and `rest-cors` (10) after the user surfaced — through frontend integration testing — that the game cannot be played end-to-end through the public API: Player A (room creator) gets `gameId: null` back from `POST /api/rooms` and the backend never tells them when Player B joins. The frontend repo (`chess-game`) had already closed features 4 and 5 (REST room and game integration) and its `progress/current.md` listed this exact scope as cross-repo items #2 (GET /api/rooms/{id}, must-have) and #3 (STOMP topic, nice-to-have) of its post-feature-5 punch list. The feature shipped both mechanisms together because shipping only one leaves the frontend incomplete: STOMP-only requires perfect subscription timing (no replay); GET-only forces wasteful polling steady load. The combined pattern is what Lichess / chess.com use for real-time multiplayer lobbies.

**Two-mechanism approach:**

1. **`GET /api/rooms/{id}`** returns `RoomDetailsResponse { roomId, players[{id, displayName, role}], gameId (nullable), status }`. Status enum is the backend-native `WAITING_FOR_PLAYER` / `ACTIVE` / `CLOSED` — no mapping to UI vocabulary. Role is derived from join order at the boundary (`players[0]` = WHITE, `players[1]` = BLACK) rather than added to the domain `Player` record; the rule lives in a `RoomDetailsMapper @Component` so it is unit-testable in isolation. 404 reuses the existing `ROOM_NOT_FOUND` from feature 6.6's 9-code allowlist (no expansion). The endpoint is idempotent, unauth (room id is the shared secret), and matches the project's existing security posture.

2. **`/topic/rooms/{roomId}`** is a polymorphic STOMP topic with a `type` discriminator field. Today's only variant is `RoomJoinedEvent { type: "ROOM_JOINED", roomId, gameId, blackPlayer }`. The `type` constant is set via a convenience constructor so callers cannot pass a wrong value; Jackson serializes the field as a plain JSON string — no `@JsonTypeInfo` machinery. The event hierarchy is a Java 17 `sealed interface RoomEvent permits RoomJoinedEvent`, giving compile-time exhaustiveness when future variants (`RoomClosedEvent`, `PlayerLeftEvent`) are added. The design rule introduced and documented: **polymorphic topics get the `type` discriminator; single-event-type topics (`/topic/games/{gameId}`, `/topic/games/{gameId}/viewers`) don't**. `MoveEvent` and `ViewerCountEvent` are intentionally NOT retrofitted.

**The broadcast point** sits in `RoomService.joinRoom` immediately after the `roomStore.compute(...)` block returns (room + game both durable in Redis) and before the REST `return`. Wrapped in try/catch + WARN log, mirroring `GameService.broadcastMoveEvent`. The broadcast is best-effort; if it fails, Player A's GET-endpoint fallback catches it. This is also why both mechanisms had to ship together.

**Storage seam extended**: `GameStore` gained `findByRoomId(String roomId) → Optional<Game>` to resolve roomId → gameId for the GET endpoint. `RedisGameStore` implements it via Redis `SCAN game:* + filter by roomId field`. This was a deliberate trade-off documented inline: alternatives were a secondary `roomId:{roomId} → gameId` index (heavier write path, stale-on-archive risk) or carrying `gameId` on the `Room` domain record (cross-aggregate ref). For a single-instance backend with 24h-TTL-bounded keyspace and a once-per-GET call path (no hot loop), SCAN is the right complexity-vs-benefit trade. The JavaDoc was polished post-review to state the cost explicitly: O(N) over the `game:*` keyspace, bounded by the TTL and single-instance traffic. A secondary index is the documented future optimisation when concurrent active game cardinality grows beyond the t3.micro baseline.

**Reviewer outcome**: APPROVED in round 1 with three out-of-scope observations (none blocking): (a) the SCAN JavaDoc lacked the explicit big-O notation — polished as a one-line addition after the user picked the "polish, then close" path; (b) a secondary index is a polish opportunity for when concurrent games scale; (c) `RoomLifecycleIT` uses a 1s `Thread.sleep` after subscribe as the subscribe-ack delay, matching the existing pattern in `GameWebSocketIT`. The user chose to keep the sleep (changing one IT but not the other would create inconsistency).

**Cross-repo follow-up**: the frontend repo can now plan its `creator-game-discovery` feature (priority 5.5 per its own current.md). Polling `GET /api/rooms/{id}` every 2-3s while `room.gameId === null` is the documented baseline; subscribing to `/topic/rooms/{roomId}` and reacting to `RoomJoinedEvent` is the optional real-time upgrade. The contract is in `docs/architecture.md`; the frontend can align without inspecting backend source.

**Files touched:**

Created:
- `src/main/java/io/github/dariogguillen/chess/web/room/RoomDetailsResponse.java` — response DTO record.
- `src/main/java/io/github/dariogguillen/chess/web/room/PlayerInRoom.java` — nested record with id/displayName/role.
- `src/main/java/io/github/dariogguillen/chess/web/room/RoomDetailsMapper.java` — `@Component` deriving roles from list index.
- `src/main/java/io/github/dariogguillen/chess/websocket/RoomEvent.java` — sealed interface; `type()` + `roomId()`.
- `src/main/java/io/github/dariogguillen/chess/websocket/RoomJoinedEvent.java` — record with `type = "ROOM_JOINED"` constant via convenience constructor.
- `src/test/java/io/github/dariogguillen/chess/web/room/RoomDetailsMapperTest.java` — 2 unit tests (WHITE-only and WHITE+BLACK derivation).
- `src/test/java/io/github/dariogguillen/chess/web/room/RoomDetailsControllerIT.java` — 3 ITs (waiting / active / 404).
- `src/test/java/io/github/dariogguillen/chess/websocket/RoomLifecycleIT.java` — 4 STOMP ITs (happy path, late subscriber, idempotency on 409 ROOM_FULL, wire-format discriminator via `byte[]` payload).
- `notes/09.5-room-lifecycle-events.md` — feature note (8 decisions documented with alternatives, 5 Scala/Typelevel parallels, gotchas, file map).

Modified:
- `src/main/java/io/github/dariogguillen/chess/web/room/RoomController.java` — adds `GET /api/rooms/{id}` with `@Tag` / `@Operation` / `@ApiResponse 200/404`.
- `src/main/java/io/github/dariogguillen/chess/service/RoomService.java` — constructor-injects `SimpMessagingTemplate`; exposes `findById` and `findGameIdByRoomId`; broadcasts `RoomJoinedEvent` from `joinRoom` outside the atomic block.
- `src/main/java/io/github/dariogguillen/chess/service/GameStore.java` — new `findByRoomId(String)` seam.
- `src/main/java/io/github/dariogguillen/chess/cache/RedisGameStore.java` — implements `findByRoomId` via `SCAN game:*` + filter; JavaDoc polished post-review to state O(N) cost explicitly.
- `docs/architecture.md` — REST contract section gains "Room read endpoint" subsection; STOMP contract section gains `/topic/rooms/{roomId}` with the discriminator pattern, the "polymorphic topic gets the discriminator" design rule, the JSON example, and the no-replay caveat.

Deleted: none.

**Final test totals**: 92 unit + 70 IT = **162 tests**, all green, zero skips, zero failures, zero errors. `./init.sh` exits 0.

**Deployment note**: the change is live in code and tests but not yet in production. Production deploys via `.github/workflows/deploy.yml` on push to `main`. STOMP topic registration is automatic (the broker prefix `/topic` was already configured in feature 6); the GET endpoint is a plain REST addition; no infra change required. Once deployed, the frontend's `creator-game-discovery` feature lights up immediately.

**Feature note**: `notes/09.5-room-lifecycle-events.md`.

---

## 2026-05-22 — rest-cors

Unblocked cross-repo item #1 from the frontend's punch list: the deployed SPA on GitHub Pages was blocked by the browser on every preflight against `/api/**` because the REST surface emitted no `Access-Control-Allow-Origin` header. Only the WebSocket handshake (`/ws`) had CORS configured (via `WebSocketConfig#setAllowedOriginPatterns` hardcoded literals). This feature adds the REST half and, in the process, eliminates the drift surface the two-config approach would have introduced.

**The drift fix** is the decision that elevates the feature above "add a WebMvcConfigurer": rather than copy the WS-side hardcoded origin list into the new REST config, the list is extracted to `CorsProperties` — a `@ConfigurationProperties("chess.cors")` record — and both layers read from it. `application.yml` exposes the list with an env-var override (`CHESS_CORS_ALLOWED_ORIGIN_PATTERNS`) so a Vercel preview or other ad-hoc origin can be added in production without a recompile. The default value matches the previous hardcoded WS list verbatim (`https://dariogguillen.github.io`, `http://localhost:*`).

**Design decisions** (each with rejected alternative + reasoning, all documented in the feature note):

1. **`WebMvcConfigurer.addCorsMappings`** — vs `@CrossOrigin` per controller (distributes policy across files, no single review point) and Caddy-layer CORS (couples policy to deploy infra, leaves local dev silent).
2. **Single source of truth via `@ConfigurationProperties`** — vs two hardcoded copies that drift. The right moment to extract is when creating the second copy, not after they diverge.
3. **`allowedOriginPatterns` (not `allowedOrigins`)** — Spring 6+ contract when wildcards or credentials are in play. Matches the WS side.
4. **Methods `GET, POST, PUT, DELETE, OPTIONS`** — GET/POST cover today's surface; PUT/DELETE futureproof for CRUD; OPTIONS is the preflight method itself.
5. **Headers `Content-Type, Accept`** — no `Authorization` because there is no auth in the codebase yet; adding it preemptively is dead config that implies a security model we don't have. When auth lands, the list expands as part of that feature.
6. **`allowCredentials: false`** — the frontend is stateless JSON, identity is the `playerId` UUID carried in body/path. The `false` posture keeps the contract honest and forbids future accidental cookie sharing without a conscious change here.
7. **`maxAge: 3600`** — standard preflight cache (1 hour), reduces overhead without being excessive.
8. **`/api/**` mapping** — covers today's REST surface as one unit; no per-endpoint overrides needed.

**Spring's drift-canary behavior** matched the plan prediction: a preflight from a disallowed origin (`https://evil.example`) returns 403 AND omits the `Access-Control-Allow-Origin` header. The IT asserts both: the status code AND `header().doesNotExist("Access-Control-Allow-Origin")`. This locks in that a future origin-list edit cannot accidentally widen the surface past intent.

**WebSocket regression signal** worked as intended: `WebSocketConfig` was refactored to read from `CorsProperties` instead of hardcoded literals, but the three WS ITs (`GameWebSocketIT`, `ViewerCountIT`, `RoomLifecycleIT`) all stayed green WITHOUT MODIFICATION — proves the refactor preserved behavior since the patterns are identical.

**Reviewer outcome**: APPROVED in round 1 with one out-of-scope observation: `docs/architecture.md:334` cross-referenced the CORS section as "below" but the section actually lands above (line 231 vs 330). Polished as a one-word fix ("below" → "above") before close.

**Caddy is unchanged.** CORS is fully an application concern; Caddy passes the headers through. No deploy workflow change, no Terraform change, no infra touch.

**Files touched:**

Created:
- `src/main/java/io/github/dariogguillen/chess/config/CorsProperties.java` — `@ConfigurationProperties("chess.cors")` record with non-empty invariant and defensive `List.copyOf` in the compact constructor.
- `src/main/java/io/github/dariogguillen/chess/config/CorsConfig.java` — `@Configuration implements WebMvcConfigurer`, `@EnableConfigurationProperties(CorsProperties.class)`, `addCorsMappings("/api/**")` with the policy decisions above.
- `src/test/java/io/github/dariogguillen/chess/config/CorsConfigIT.java` — 4 ITs: GitHub Pages preflight + headers, localhost preflight + headers, drift canary (403 + header absent), actual POST cross-origin (201 + header present).
- `notes/10-rest-cors.md` — feature note covering the decisions, Scala/Typelevel parallels (pureconfig `ConfigSource[F].load[CorsConfig]`, http4s `CORS.policy[F]` middleware, regex/glob mode of `CORSPolicy`), gotchas (`allowedOriginPatterns` vs `allowedOrigins`, Spring's 403-and-omit behavior, two-config drift risk).

Modified:
- `src/main/java/io/github/dariogguillen/chess/config/WebSocketConfig.java` — constructor-injects `CorsProperties`; hardcoded origin pair removed; `@EnableConfigurationProperties(CorsProperties.class)` added; JavaDoc points at `CorsProperties` as source of truth.
- `src/main/resources/application.yml` — new `chess.cors.allowed-origin-patterns` block with `CHESS_CORS_ALLOWED_ORIGIN_PATTERNS` env-var default matching the previous WS list.
- `docs/architecture.md` — new top-level "## CORS" section between "API contract" and "STOMP API contract" covering shared property, REST policy, STOMP cross-reference, Caddy pass-through. The STOMP-side "Allowed origins" subsection is now a pointer to the new CORS section (no duplicate content). Polished post-review to fix the "below" → "above" cross-reference.

Deleted: none.

**Final test totals**: 92 unit + 74 IT = **166 tests**, all green, zero skips, zero failures, zero errors. `./init.sh` exits 0.

**Deployment note**: production EC2 picks up the new CORS headers on the next deploy automatically. Caddy passes them through unchanged. The frontend manual smoke test (open the GH Pages SPA, create a room, join from a second browser) is the closing signal — the first cross-origin request that succeeds where it failed before validates the feature end-to-end.

**Feature note**: `notes/10-rest-cors.md`.

---

## 2026-05-22 — disconnect-handling

Core lifecycle for player disconnect/reconnect. The feature was deliberately split into 11 (core: detect → grace → reconnect OR abandon + archive + terminal broadcast) and 11.5 (disconnect-notifications: mid-grace countdown UX + sealed-interface refactor of `/topic/games/{gameId}`). The acceptance criterion as written in `feature_list.json` is fully covered by 11; 11.5 is portfolio UX polish that introduces new STOMP events for the opponent's reconnecting-countdown banner. The user chose the split before planning began.

**Approach.** Four new components compose the lifecycle:

1. **`PlayerSessionTracker`** — mirrors `ViewerCountTracker`'s structure. Listens `SessionSubscribeEvent` / `SessionDisconnectEvent`. Reuses the existing `playerId` STOMP native-header convention (with the same tolerant `parseUuidOrNull` parser). On SUBSCRIBE to `/topic/games/{gameId}`, if the `playerId` matches `game.white().id()` or `game.black().id()` the session is recorded as a player session AND any pending grace-period timer for that `(playerId, gameId)` is cancelled (this is the reconnect path). On DISCONNECT, if the game is still non-terminal, a grace timer is started. Spectators are untouched — they keep being handled by `ViewerCountTracker` independently, and a single session can be both a player and a spectator session without conflict.
2. **`GracePeriodManager`** — programmatic per-(player, game) one-shot timers via `TaskScheduler.schedule(task, Instant)`. Internal `Map<GracePeriodKey, ScheduledFuture<?>>`. Public API: `startGracePeriod`, `cancelGracePeriod`, `isActive`. The grace period duration comes from a new `@ConfigurationProperties("chess.disconnect")` with a `Duration gracePeriod` default of 60s, env-var override `CHESS_DISCONNECT_GRACE_PERIOD`. **A race condition was found and closed during implementation**: cancel-then-fire-or-fire-then-cancel interleavings could remove the key under either thread, with the late side assuming a no-op while the other was about to invoke abandon. The fix is a per-key `ReentrantLock` (a fresh `StripedKeyLock` instance — the manager's key domain is independent of the store's) acquired in `start`, `cancel`, AND the scheduled task body's `fire` method. The three operations are now mutually exclusive. Documented in the manager's class JavaDoc and in the feature note's "Gotchas" section.
3. **`GameAbandonService`** — `abandon(gameId, abandonedBy)`. Wraps a `gameStore.compute(...)` that flips the game's status to `ABANDONED` only on the non-terminal branch (idempotent: already-terminal games short-circuit). The transition signal is an `AtomicBoolean transitioned` set inside the lambda; the triple guard `updated == null || !transitioned.get()` distinguishes unknown-game / already-terminal / fresh-flip. The archive call (`gameHistoryService.archive(updated)`) runs **outside** the Redis `compute` lock — the original plan suggested inside (mirroring `GameService.applyMove`), but ABANDONED is irrevocable, no follow-up move can race against the commit, and a JDBC round-trip inside the Redis per-key lock buys nothing. The asymmetry vs the move-terminal path is documented in the service's JavaDoc. The terminal broadcast is wrapped in try/catch + WARN log mirroring `GameService.broadcastMoveEvent`. Winner derivation: the opposite of `abandonedBy`, carried in the event payload so the frontend doesn't have to compute it.
4. **`GameAbandonedEvent`** — a plain record broadcast on the existing `/topic/games/{gameId}` topic. **NO `type` discriminator field**, **NOT** a sealed-interface variant. The plan deliberately deferred the sealed-interface refactor of `MoveEvent` + `GameAbandonedEvent` + the upcoming `PlayerDisconnectedEvent`/`PlayerReconnectedEvent` to feature 11.5, where all three new variants land coherently. Adding the discriminator on `GameAbandonedEvent` alone would create asymmetry with `MoveEvent` and drift risk; retrofitting `MoveEvent` here would pre-empt 11.5's scope.

**Scheduling infrastructure introduced.** `@EnableScheduling` lives on a new `config/SchedulingConfig` (NOT on `ChessApplication` — keeps the application class minimal and matches the `CorsConfig`/`RedisConfig` shape). A `ThreadPoolTaskScheduler` bean is registered with pool size 2, daemon threads, and a `chess-scheduler-` prefix. The `@Scheduled` annotation is NOT used; the manager schedules tasks programmatically via the bean.

**Two archive call sites — deliberate, no shared helper.** The existing `GameService.applyMove` call site (feature 9) for terminal-by-move and the new `GameAbandonService.abandon` site for terminal-by-timeout duplicate exactly one line (`gameHistoryService.archive(updated)`). Extracting a `GameTerminationHelper` was considered and rejected as premature abstraction: the two contexts are different (move applied vs status mutated by timeout) and the shared helper would obscure that. Documented in the note.

**Server-restart limitation acknowledged.** If the backend restarts while grace-period timers are pending, the in-memory `Map<GracePeriodKey, ScheduledFuture<?>>` is lost. Affected games stay in `ONGOING` until the next event terminates them. Mitigation (persisting timer state to Redis with TTL + a restart-time recovery sweep) is heavyweight for the single-instance deploy and a polish opportunity for a future feature, NOT this one.

**Cross-repo coordination.** `/topic/games/{gameId}` now carries two event types (`MoveEvent` + `GameAbandonedEvent`) distinguished by **shape** (presence of `abandonedBy` vs `from`/`to`). The frontend's `Play.tsx` needs to handle the new event and transition to "game over" with the opposite-of-abandonedBy as winner. The contract is in `docs/architecture.md`. 11.5 will retrofit a `type` field and the distinction becomes `switch (event.type)`. The frontend adopts on its own schedule.

**Reviewer outcome**: APPROVED in round 1, no rejections. Two out-of-scope observations: (a) `@SpyBean` is deprecated in favour of `@MockitoSpyBean` (Spring Boot 3.4+), and (b) `DisconnectHandlingIT.SUBSCRIBE_REGISTRATION_DELAY_MS = 1_000L` is a literal `Thread.sleep` matching the existing baseline in `GameWebSocketIT` and `RoomLifecycleIT`. The user chose to polish (a) before close — the implementer migrated `GameAbandonServiceIT` to `@MockitoSpyBean`, updated the import, fixed the corresponding `{@link …}` JavaDoc reference, and updated the note's "Key decisions" and "Gotchas" sections to reflect the migration. A grep across the whole test suite confirmed there were no other `@SpyBean` usages — the fix was fully contained. Observation (b) is left as a future test-infrastructure polish (changing the sleep in one IT without the other two would create inconsistency).

**Files touched.**

Created:
- `src/main/java/io/github/dariogguillen/chess/config/DisconnectProperties.java` — `@ConfigurationProperties("chess.disconnect")` record with positive-Duration invariant.
- `src/main/java/io/github/dariogguillen/chess/config/SchedulingConfig.java` — `@EnableScheduling`, `ThreadPoolTaskScheduler` bean, `@EnableConfigurationProperties(DisconnectProperties.class)`.
- `src/main/java/io/github/dariogguillen/chess/service/GracePeriodManager.java` — start/cancel/isActive timers, per-key `StripedKeyLock`, race-condition lock on start/cancel/fire.
- `src/main/java/io/github/dariogguillen/chess/service/GameAbandonService.java` — idempotent abandon via `compute` + archive (outside the block) + broadcast.
- `src/main/java/io/github/dariogguillen/chess/websocket/PlayerSessionTracker.java` — `@EventListener` on SessionSubscribeEvent / SessionDisconnectEvent, mirrors ViewerCountTracker.
- `src/main/java/io/github/dariogguillen/chess/websocket/GameAbandonedEvent.java` — plain record, no `type` field (deferred to 11.5).
- `src/test/java/io/github/dariogguillen/chess/service/GracePeriodManagerTest.java` — 5 unit tests (start-isActive, cancel-prevents-fire, double-start-replaces, fires-and-clears, cancel-on-unknown).
- `src/test/java/io/github/dariogguillen/chess/service/GameAbandonServiceIT.java` — 3 ITs (non-terminal happy, already-terminal no-op, unknown no-op) using `@MockitoSpyBean SimpMessagingTemplate`.
- `src/test/java/io/github/dariogguillen/chess/websocket/DisconnectHandlingIT.java` — 3 end-to-end STOMP ITs with `@TestPropertySource("chess.disconnect.grace-period=300ms")`.
- `notes/11-disconnect-handling.md` — feature note (template sections + 6 decisions documented with alternatives + 4 Scala/Typelevel parallels + race-condition Gotcha).

Modified:
- `src/main/java/io/github/dariogguillen/chess/domain/Game.java` — added `Game withStatus(GameStatus newStatus)` helper (single new method, no field shifts).
- `src/main/resources/application.yml` — `chess.disconnect.grace-period: ${CHESS_DISCONNECT_GRACE_PERIOD:60s}` under the existing `chess:` namespace.
- `docs/architecture.md` — new "Disconnect handling" subsection covering the four components, the two archive call sites with the no-shared-helper rationale, the server-restart limitation, and the GameAbandonedEvent shape with the deferred-discriminator → feature 11.5 rationale.

Deleted: none.

**Final test totals**: 97 unit + 80 IT = **177 tests**, all green, zero skips, zero failures, zero errors. `./init.sh` exits 0.

**Deployment note**: the change is live in code and tests but not yet in production. The next push to `main` triggers `.github/workflows/deploy.yml`, which rebuilds the image, pushes to ECR, and restarts the EC2 container. No infra change required — `@EnableScheduling` and the `TaskScheduler` bean are app-internal; the new env var `CHESS_DISCONNECT_GRACE_PERIOD` has a 60s default so prod works without setting it.

**Feature note**: `notes/11-disconnect-handling.md`.

---

## 2026-05-22 — disconnect-notifications

The planned follow-up to feature 11. Feature 11 shipped the disconnect lifecycle (detect → grace → reconnect or abandon + archive + terminal broadcast) but deliberately deferred two pieces: the mid-grace UX events for the opponent's reconnecting-banner countdown, and the sealed-interface retrofit of `/topic/games/{gameId}` (which carried two event types after feature 11 — `MoveEvent` and `GameAbandonedEvent` — distinguishable only by shape). This feature closes both pieces together, mirroring feature 9.5's `RoomEvent` family pattern on the games topic.

**Sealed-interface family**. `sealed interface GameStateEvent permits MoveEvent, GameAbandonedEvent, PlayerDisconnectedEvent, PlayerReconnectedEvent` with abstract `String type()` and `UUID gameId()`. Java 17 sealed semantics give compile-time exhaustiveness when future variants land. The `type` field is a regular String component on each record set via a convenience constructor that defaults the constant (`"MOVE"`, `"GAME_ABANDONED"`, `"PLAYER_DISCONNECTED"`, `"PLAYER_RECONNECTED"`); no `@JsonTypeInfo`. Same call as feature 9.5: keeping the discriminator visible at the source beats hiding it behind Jackson annotations.

**Backward-compatible retrofit**. `MoveEvent` and `GameAbandonedEvent` gain a leading `String type` field but their existing convenience constructors are preserved without changes — every current call site (`GameService.broadcastMoveEvent`, `GameAbandonService.abandon`, every test that constructs an event) compiles unchanged. The wire-side change is also backward-compatible: Jackson's `FAIL_ON_UNKNOWN_PROPERTIES=false` default means the frontend's existing typed deserialisation ignores the new field, and the existing `GameWebSocketIT` / `GameAbandonServiceIT` stayed UNTOUCHED on disk — the strongest possible regression signal that the retrofit didn't break the contract.

**The new mid-grace events** are emitted by `PlayerSessionTracker` (constructor-injected with `SimpMessagingTemplate`, `DisconnectProperties`, `Clock` in addition to its existing deps). On disconnect, after `GracePeriodManager.startGracePeriod`, a `PlayerDisconnectedEvent` is broadcast carrying the player's UUID, their side (derived at the boundary from `game.white()` vs `game.black()`), the disconnect timestamp, and an **absolute `gracePeriodEndsAt: Instant`** — explicitly NOT a `secondsRemaining: int` delta or per-second tick events. Three approaches were considered: per-second ticks (server source of truth but 60 events per disconnect + scheduler complexity), `secondsRemaining` snapshot (simple but stale-on-the-wire — by the time the frontend receives it, the value is wrong by N ms), and the chosen `gracePeriodEndsAt` absolute deadline (1 broadcast, value never stale because it's an instant not a delta, client computes `(deadline - now()) / 1000` once per render tick using its local clock). This matches how chess.com and Lichess send timer state and matches http4s/akka-typed `Deadline` types in the Scala/Typelevel parallels documented in the note. On reconnect, the SUBSCRIBE handler calls `gracePeriodManager.cancelGracePeriod(...)`; the manager's signature changed from `void` to `boolean` returning whether a pending timer was actually found and cancelled, and the broadcast of `PlayerReconnectedEvent` is gated on `true` — the cancel-vs-fire race (reconnect arriving right as the timer fires) is now silent on the wire because the cancel sees `false` and emits nothing, while the timer-fire path proceeds to `GameAbandonService.abandon` and the `GameAbandonedEvent` becomes the source of truth.

**Three deviations from the plan**, all minor and approved by the reviewer:

1. `PlayerSessionTracker.onDisconnect` also skips the broadcast when the game does not exist (null lookup), not just when it is terminal. A defensive merge of two related guards.
2. The order on disconnect is `startGracePeriod` first, then broadcast — so the timer is guaranteed to exist by the time the opponent's UI starts counting down from `gracePeriodEndsAt`.
3. The third IT (`disconnect_onAlreadyTerminalGame_noBroadcast`) skips the Postgres-row-count assertion that the plan suggested, because `DisconnectHandlingIT` already covers the no-double-archive invariant via `historyRepository.count()`. Keeping the new IT focused on the wire surface avoids overlap.

**Two existing tests in `DisconnectHandlingIT` were modified** with reviewer-approved justification: the topic's contract was legitimately expanded by this feature (broadcasts increased from 1 to 2 events on the timeout-and-abandon path and from 0 to 2 on the reconnect-within-grace path), so the tests had to drain the new events. All load-bearing invariants stayed: test 1 still asserts the `GameAbandonedEvent` shape and the no-double-archive guard; test 2 still asserts game stays `ONGOING` and no `GameAbandonedEvent` arrives. Test 3 (disconnect on already-terminal game) was byte-identical because the terminal-game guard suppresses both the new broadcast and the timer.

**The "polymorphic topic gets the discriminator" rule** introduced in feature 9.5 for `/topic/rooms/{roomId}` is now applied codebase-wide. The rule was lifted to a top-level paragraph in the STOMP contract section of `docs/architecture.md` so future readers find it before each topic-specific subsection. The corollary — "single-event-type topics don't get a discriminator" — keeps `/topic/games/{gameId}/viewers` (which carries only `ViewerCountEvent`) free of the dead-weight field.

**Reviewer outcome**: APPROVED in round 1, no rejections. Three out-of-scope observations: (a) cosmetic Javadoc on `PlayerDisconnectedEvent` had a `})` visual cluster (closing `@code` brace adjacent to closing paren of an aside); polished by replacing the parenthetical with em-dashes before close. (b) `RoomEvent` doesn't share a common `XxxId()`-style base method with `GameStateEvent`; intentional (rooms and games have different identifiers) but a flag if a third polymorphic topic ever emerges. (c) `DisconnectNotificationsIT` uses a 2s grace period plus a `Thread.sleep(800ms)` between events, adding ~6s of test budget; same trade-off the implementer documented as a "Gotcha" — accepted.

**Cross-repo coordination after close**. The frontend repo's `Play.tsx` now has a cleaner path:
- Migrate from shape-based discrimination on `/topic/games/{gameId}` (the awkward `if ('abandonedBy' in event)` check needed post-feature-11) to `switch (event.type)` — backward-compatible, can ship at any time.
- Handle `PlayerDisconnectedEvent` → render the reconnecting banner; use `gracePeriodEndsAt` for a local-clock countdown (one `requestAnimationFrame` loop or `setInterval`, frontend's choice).
- Handle `PlayerReconnectedEvent` → clear the banner.

The contract is in `docs/architecture.md` for the frontend to align without inspecting backend source.

**Files touched.**

Created:
- `src/main/java/io/github/dariogguillen/chess/websocket/GameStateEvent.java` — sealed interface with 4 permits; codebase-wide design rule documented in JavaDoc.
- `src/main/java/io/github/dariogguillen/chess/websocket/PlayerDisconnectedEvent.java` — record with `type = "PLAYER_DISCONNECTED"`, carries absolute `gracePeriodEndsAt`.
- `src/main/java/io/github/dariogguillen/chess/websocket/PlayerReconnectedEvent.java` — record with `type = "PLAYER_RECONNECTED"`.
- `src/test/java/io/github/dariogguillen/chess/websocket/DisconnectNotificationsIT.java` — 3 STOMP ITs (disconnect → both events emitted; reconnect within grace → DISCONNECTED+RECONNECTED, no ABANDONED; disconnect on terminal game → silent).
- `notes/11.5-disconnect-notifications.md` — feature note (6 decisions documented with alternatives + 5 Scala/Typelevel parallels + 4 gotchas + file map).

Modified:
- `src/main/java/io/github/dariogguillen/chess/websocket/MoveEvent.java` — retrofitted with leading `String type` field + `TYPE` constant + convenience constructor; implements `GameStateEvent`.
- `src/main/java/io/github/dariogguillen/chess/websocket/GameAbandonedEvent.java` — same retrofit pattern with `"GAME_ABANDONED"` constant.
- `src/main/java/io/github/dariogguillen/chess/websocket/PlayerSessionTracker.java` — three new constructor-injected deps; broadcasts the two new events with try/catch + WARN log mirroring `GameAbandonService.abandon`.
- `src/main/java/io/github/dariogguillen/chess/service/GracePeriodManager.java` — `cancelGracePeriod` signature changed from `void` to `boolean`; semantic documented in JavaDoc.
- `src/test/java/io/github/dariogguillen/chess/websocket/DisconnectHandlingIT.java` — two existing tests adjusted to drain the new broadcasts; third test byte-identical.
- `docs/architecture.md` — "polymorphic topic gets the discriminator" rule lifted to a codebase-wide top-level paragraph; new "GameStateEvent family" subsection with example JSON for all four variants; "Disconnect handling" subsection rewritten from "feature 11.5 will broadcast" to present tense.

Deleted: none.

**Final test totals**: 97 unit + 83 IT = **180 tests**, all green, zero skips, zero failures, zero errors. `./init.sh` exits 0.

**Deployment note**: production EC2 picks up the new event types automatically on the next deploy. The retrofit is wire-backward-compatible — the existing frontend keeps working with shape-based discrimination until it migrates to `switch (event.type)`. No infra change required.

**Feature note**: `notes/11.5-disconnect-notifications.md`.

---

## 2026-05-22 — cors-x-player-id

A bug fix inserted between features 11.5 and 12 (priority 11.7) after the frontend reported during local E2E testing that any cross-origin `POST /api/games/{id}/moves` would be blocked by the browser preflight. The cause was a silent cross-feature gap: feature 5 (`game-rest-api`) introduced the `@RequestHeader("X-Player-Id")` requirement on the moves endpoint, and feature 10 (`rest-cors`) declared the CORS `allowedHeaders` allow-list without including it. The two features shipped in different sessions and the cross-link was missed; local IT didn't catch it because the existing `CorsConfigIT` preflight tests only asked for `Content-Type` — a tautology against the existing allow-list, not a contract check against the actual server-side header requirements.

**The fix** is one line in `CorsConfig.allowedHeaders` (`"Content-Type", "Accept", "X-Player-Id"`) plus a regression test in `CorsConfigIT` (`preflight_playerIdHeader_returnsCorsHeaders`) that OPTIONS against `/api/games/{gameId}/moves` with `Access-Control-Request-Headers: Content-Type, X-Player-Id` and asserts the `Access-Control-Allow-Headers` response contains `X-Player-Id`. The class-level JavaDoc on `CorsConfig` was rewritten to mention the new entry, link it to `POST /api/games/{id}/moves`, and preserve the `Authorization` deliberate-omission rationale from feature 10. `docs/architecture.md` follows.

**Out of scope**: the deliberate omission of `Authorization` stays — no auth feature ships yet; allow-listing it preemptively remains the same anti-pattern feature 10 rejected.

**Reviewer outcome**: APPROVED in round 1, no rejections. Two out-of-scope observations: (a) the `feature_list.json` acceptance criterion for this feature attributed the X-Player-Id introduction to "feature 9 / 9.5" when the correct attribution is feature 5; polished before close to read "feature 5 game-rest-api without updating the CORS allow-list added later in feature 10". (b) the user's local working tree had an unrelated `docker-compose.yml` modification (the `app:` service block commented out, likely debug while running the backend via `./mvnw spring-boot:run`); flagged to the user so it doesn't ride along into the commit; not part of this feature.

**Files touched**:

Created:
- `notes/11.7-cors-x-player-id.md` — feature note documenting the cross-feature coordination gap, the codec/derivation Scala parallel, and the simple-vs-preflighted header browser rules.

Modified:
- `src/main/java/io/github/dariogguillen/chess/config/CorsConfig.java` — `allowedHeaders` gains `X-Player-Id`; JavaDoc updated; constructor injection and the rest of the policy unchanged.
- `src/test/java/io/github/dariogguillen/chess/config/CorsConfigIT.java` — new `preflight_playerIdHeader_returnsCorsHeaders` IT placed alongside the other `preflight_*` tests; added `java.util.UUID` import; existing 4 tests byte-identical.
- `docs/architecture.md` — CORS section's allowed-headers paragraph lists all three with rationale.

Deleted: none.

**Final test totals**: 97 unit + 84 IT = **181 tests**, all green, zero skips, zero failures. `./init.sh` exits 0.

**Cross-repo note**: this closes item #1 of the frontend's post-feature-11.5 cross-repo flags. Item #2 (`RoomService.findById` 404 on ACTIVE rooms) and item #3 (`RoomJoinedEvent` broadcast not reaching subscribers) remain open and are tracked as future work pending the user's TRACE-log diagnosis of #3 and a re-read of the frontend's current.md for #2.

**Feature note**: `notes/11.7-cors-x-player-id.md`.

---

## 2026-05-22 — broadcast-observability

A small operational-logging feature inserted at priority 11.8 (between cors-x-player-id and docker-compose) after the bug #3 investigation in feature 11.7 made the observability gap impossible to ignore. The diagnosis of the frontend's reported "RoomJoinedEvent not reaching subscribers" required enabling TRACE on three Spring packages (`org.springframework.web.socket`, `.messaging`, `.messaging.simp.broker`) to confirm a single line — `Broadcasting to 1 sessions.` — from `SimpleBrokerMessageHandler`. That confirmation would have been visible from production-level logs if every `messagingTemplate.convertAndSend(...)` site emitted an INFO after a successful return. Before this feature only the failure path logged (WARN inside the catch); the success path was silent.

**The fix** is six INFO log lines, one after each `convertAndSend` call, across five files:

| Site | File | Identifiers in log |
| --- | --- | --- |
| `broadcastRoomJoinedEvent` | `service/RoomService.java` | destination, gameId, joinerId |
| `broadcastMoveEvent` | `service/GameService.java` | destination, movedBy, status |
| `abandon` (broadcast tail) | `service/GameAbandonService.java` | destination, abandonedBy, winnerId |
| `broadcastDisconnected` | `websocket/PlayerSessionTracker.java` | destination, playerId, side, gracePeriodEndsAt |
| `broadcastReconnected` | `websocket/PlayerSessionTracker.java` | destination, playerId, side |
| `broadcast` | `websocket/ViewerCountTracker.java` | destination, count |

Every line goes INSIDE the existing `try` block, immediately after `convertAndSend`, with the existing WARN-on-`RuntimeException` catch untouched. The two states (success / failure) are now both visible at INFO/WARN; previously only failure was.

**Five decisions documented in the note**, each with the alternative + reasoning:

1. **INFO level, not DEBUG.** The bug investigation just proved these logs have operational value at production-grade visibility. DEBUG would gate them behind a developer flag and recreate the original gap.
2. **After `convertAndSend`, not before.** The WARN on failure already covers "did the method enter and throw?" The "after" line specifically confirms `convertAndSend` returned without throwing — which is the exact information that was missing during the bug #3 diagnosis.
3. **No shared helper, inline at each site.** Six sites with per-event identifiers (UUIDs, short codes, enum names, Instants) each emit a slightly different shape. A shared `broadcastWithLog(destination, payload, identifiers)` would either lose the typed identifiers or take a `Map<String, Object>` which is a worse trade. Six lines of explicit code beat one over-eager abstraction.
4. **Identifiers in the log, payload omitted.** Keys are safe and useful for cross-referencing. Full payloads can be large (e.g. a `MoveEvent` with the new FEN) and would inflate INFO logs unnecessarily. Anyone wanting the payload still has TRACE.
5. **No new tests.** Operational logs are not behavioural contract. Asserting log content in a test would tie behavioural code to its observability shape, which couples two concerns that should evolve independently. The verification of correctness is manual + observed in the existing IT logs (where the new INFO lines now appear immediately after each mutation log).

**The case study** — feature 11.7's bug #3 investigation — is documented in the feature note as the load-bearing motivation. The lesson learned: a production-grade backend should never make an operator enable TRACE on framework packages to confirm "did the side effect happen". The next operator (or the user, diagnosing a similar issue in a future session) can now answer that question from INFO alone.

**Reviewer outcome**: APPROVED in round 1, no rejections, no out-of-scope observations beyond the operational note that the user has feature 11.7 and 11.8 staged together in the working tree and should split them into two commits (one per logical unit) when committing.

**Files touched**:

Created:
- `notes/11.8-broadcast-observability.md` — feature note with the case study, five decisions, Scala/Typelevel parallels (`flatTap` on success branch, "no shared helper" rationale), and gotchas covering MoveEvent log volume, UUID non-sensitivity, and the WARN-doesn't-double-log invariant.

Modified:
- `src/main/java/io/github/dariogguillen/chess/service/RoomService.java` — INFO log after `convertAndSend` in `broadcastRoomJoinedEvent`; class-JavaDoc cross-reference.
- `src/main/java/io/github/dariogguillen/chess/service/GameService.java` — INFO log in `broadcastMoveEvent`; class-JavaDoc cross-reference.
- `src/main/java/io/github/dariogguillen/chess/service/GameAbandonService.java` — INFO log in the `abandon` broadcast tail; class-JavaDoc cross-reference (spotless reflowed it).
- `src/main/java/io/github/dariogguillen/chess/websocket/PlayerSessionTracker.java` — INFO logs in both `broadcastDisconnected` and `broadcastReconnected`; class-JavaDoc cross-reference covering both.
- `src/main/java/io/github/dariogguillen/chess/websocket/ViewerCountTracker.java` — INFO log in `broadcast`; class-JavaDoc cross-reference.

Deleted: none.

**Final test totals**: 97 unit + 84 IT = **181 tests** (unchanged from before this feature — operational logs do not affect behavioural tests). `./init.sh` exits 0. `git diff --stat src/test/` was empty for this feature — the strongest possible regression signal for a no-behaviour-change feature.

**Deployment note**: the new INFO lines start appearing on the next deploy automatically. No env var, no infra change, no behavioural change. The next operator (or the user) can confirm any of the six broadcasts simply by tailing the production logs at INFO level.

**Feature note**: `notes/11.8-broadcast-observability.md`.

---

## 2026-05-25 — docker-compose

**Status:** done (closed as redundant — no implementation cycle)

**Summary:** Feature 12 (`docker-compose`) was closed without delegating to the implementer or reviewer. Its three acceptance criteria are a strict subset of feature 7's (`backend-containerize`) already-shipped deliverables, confirmed by an on-disk audit on 2026-05-25:

| Acceptance criterion (feature 12) | Where it already lives |
| --- | --- |
| `docker compose up -d` brings up app + Postgres + Redis on local ports | `docker-compose.yml` (postgres:16 on 5432, redis:7-alpine on 6379, `build: .` app on 8080, healthchecks + `depends_on: service_healthy`) — shipped in feature 7. |
| README has a 'Run locally' section | `README.md` `## Running locally` with three workflows: Testcontainers (`./mvnw spring-boot:test-run`), docker-compose (`docker compose up --build`), hybrid (`docker compose up postgres redis -d` + `./mvnw spring-boot:run`) — shipped in feature 7. |
| `./init.sh` passes | Green continuously through features 8–11.8; no docker-compose-related regression has surfaced. |

Three options were presented to the user: (A) close as redundant with a `done` flip and a documented rationale, (B) delete the entry from `feature_list.json`, (C) re-scope to invented "dev tooling polish" work. The user picked A. Rationale for A over B: deleting the entry hides the decision from anyone reading the history; closing with a clear log entry makes the deliberate-no-op visible. Rationale for A over C: manufacturing scope for the sake of a feature count is dishonest for a portfolio repo where engineering quality is the explicit value driver (per `AGENTS.md`).

No `notes/12-docker-compose.md` was produced — there is no implementation to teach from. This entry IS the documentation of the closure. The CLAUDE.md leader rule "no `done` without reviewer + passing init.sh" is honoured in spirit because the acceptance criteria were satisfied (and reviewer-validated) by feature 7's cycle; the alternative — running a no-op implementer + reviewer cycle on already-shipped code — would be performance, not verification.

**Files touched:**

- `feature_list.json` (modified: `docker-compose.status` → `done`)
- `progress/history.md` (this entry)
- `progress/current.md` (replaced with session-closed note pointing to `github-actions-ci` as next in queue)

**Feature note:** none (deliberate; see Summary).

---

## 2026-05-25 — github-actions-ci

**Status:** done

**Summary:** Added a PR validation workflow that runs `./init.sh` on every `pull_request` targeting `main`, complementing feature 7.7's `deploy.yml` (which already validates push-to-`main`). Until this feature, nothing was catching regressions BEFORE a PR merged — a bad change could only be caught after the deploy pipeline started, too late. The new `.github/workflows/ci.yml` uses a concurrency group keyed by the PR number with `cancel-in-progress: true` (a new commit cancels the in-flight check), and least-privilege `permissions.contents: read` (no `id-token: write` is needed because no AWS work happens on PRs — a deliberate separation from the deploy workflow's elevated privileges).

The shared Java setup + verify sequence — used by both `ci.yml` and `deploy.yml` — was extracted into a composite action at `.github/actions/build/action.yml` to avoid drift (precedent: feature 11.7's CORS header allow-list silently fell out of sync with the header introduced in feature 5). The composite holds two steps: `actions/setup-java@v4` (Temurin 21, Maven cache) and `run: ./init.sh` (with explicit `shell: bash`, required for composite `run:` steps). `deploy.yml` was refactored to call the composite for its prologue; the `Checkout` step and everything from `Configure AWS credentials (OIDC)` onwards (ECR build/tag/push, EC2 SSH, smoke test) are byte-identical to the pre-refactor version, so production deploy behaviour is preserved.

The original plan locked a **three-step** composite that included `actions/checkout@v4` alongside the setup-java + init.sh steps. The implementer caught this as a blocker before writing any code: a *local* composite action (`uses: ./.github/actions/build`) cannot self-include checkout because the runner needs to read `action.yml` from the workspace BEFORE it can execute any step inside the composite — an empty workspace at that point means no `action.yml` and a hard failure. The canonical pattern in GitHub's docs has `actions/checkout@v4` in the calling workflow first, then `uses: ./.github/actions/...`. The leader updated the plan to a two-step composite + caller-does-checkout shape, the implementer resumed cleanly, and the blocker discovery was captured in the feature note's Gotchas section as a portfolio-grade learning artefact rather than a planning failure to hide.

The architectural choice — composite action (B) vs. duplicate three steps (D) vs. reusable workflow (R, `workflow_call`) — was raised at planning time and decided by the user in favour of B. Composite is the idiomatic answer for shared step sequences; D risks the same drift that bit us in feature 11.7; R is overkill for step reuse (it was designed for job orchestration, with its own `runs-on` and runner pool). The trade-off is one indirection at read time (`uses: ./.github/actions/build` doesn't tell you what it does without opening the file), accepted in exchange for a single source of truth on Java version + verify invocation.

README gained a CI status badge between the H1 and the tagline, using GitHub's canonical badge URL pointing at `ci.yml`. Branch protection (block merge on failed check) is configured via the GitHub web UI by the user — out of code scope, documented in the feature note. The first real CI run will happen on the next PR opened against `main`; the close is justified on the static validation done locally (YAML parses clean under `python3 yaml.safe_load`, structure/permissions/concurrency reviewed, `./init.sh` green locally — the exact command the workflow will run) plus the fact that `deploy.yml`'s post-prologue is unchanged, so production deploy is unaffected even if `ci.yml` has a defect we missed.

Test counts unchanged: 181 (97 unit + 84 IT). No application code was touched. `actionlint` is not on the dev machine's PATH; manual YAML parsing via `python3 yaml.safe_load` confirmed all three files parse cleanly. GitHub's own parse-on-push validation remains as the fallback.

**Files touched:**

- `.github/actions/build/action.yml` (new; composite action with 2 steps — `setup-java@v4` Temurin 21 with Maven cache + `./init.sh` with `shell: bash`; top-of-file comment and `description:` field both document the checkout caveat for future readers)
- `.github/workflows/ci.yml` (new; `pull_request → main` trigger, concurrency keyed by `github.event.pull_request.number` with `cancel-in-progress: true`, `permissions: contents: read` only, two-step job: checkout + composite)
- `.github/workflows/deploy.yml` (modified; replaced `Set up Java 21` and `Run init.sh (compile + unit + integration tests)` with a single `Build and verify` step calling the composite; `Checkout` and everything from `Configure AWS credentials (OIDC)` onwards byte-identical)
- `README.md` (modified; CI status badge inserted between the H1 and the tagline)
- `notes/13-github-actions-ci.md` (new; full template coverage; Gotchas section captures the composite-action checkout caveat, the `shell:` requirement on composite `run:` steps, the `pull_request` merge-ref behaviour, and the missing `actionlint`; Decisions taken documents the B/D/R trade-off; "How this compares to what I know" includes the `sbt-github-actions` parallel)
- `feature_list.json` (modified: `github-actions-ci.status` → `done`)
- `progress/current.md` (replaced with session-closed note pointing to `readme-polish` as next)
- `progress/history.md` (this entry)

**Feature note:** `notes/13-github-actions-ci.md`.
