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
