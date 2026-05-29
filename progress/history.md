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

---

## 2026-05-25 — readme-polish (portfolio closure)

**Status:** done

**Summary:** The final feature in the plan. The README went from a 197-line work-in-progress framing to a 294-line recruiter-grade portfolio surface — same engineering depth, but reorganised around what someone unfamiliar with the project actually needs in the first 30 seconds: a one-paragraph pitch, three live URLs, a highlights block, two diagrams, and a path into the engineering depth.

The old README opened with "This repo is a portfolio project. It's a work in progress." and a "Currently in early development" Status section — both factually wrong at 22 features delivered with a production deploy and 181 tests green. The new README opens with the locked Hybrid pitch (technical capability, named differentiators, harness mentioned), then a "Try it live" block exposing the GitHub Pages frontend + the AWS-deployed backend health probe + the Swagger UI before the reader has to scroll. The plan made the live URLs the second-priority content for exactly the reason a portfolio README exists in the first place: a recruiter who cannot try the project loses the moment.

Two Mermaid diagrams were the headline addition. The first is a top-to-bottom `flowchart TB` that mirrors `docs/architecture.md`'s "Layered architecture" dependency direction (Frontend → REST `/api/**` + STOMP `/ws` → web/websocket/service/cache/persistence/domain → Redis active + Postgres durable + chesslib in-process). The second is a `sequenceDiagram` walking the full E2E flow: Player A creates a room, subscribes to `/topic/rooms/{roomId}`, Player B joins via REST, the backend writes Redis room + creates the Game, broadcasts `RoomJoinedEvent`, both players subscribe to `/topic/games/{gameId}` with the `playerId` native header, moves flow over REST with `X-Player-Id`, the backend validates + persists + broadcasts `MoveEvent`, and the terminal state archives to Postgres. The sequence diagram is the clearest possible answer to "what does this thing actually do," which is the question the prose can only answer in paragraphs. Both diagrams render natively on GitHub's Mermaid pipeline; no image artefacts in git, no out-of-band documentation toolchain to maintain.

A dedicated `## Engineering process` section was the second-largest addition. The leader/implementer/reviewer harness + persisted `progress/` state + `notes/` learning trail is one of the strongest differentiators of this project — and was completely invisible in the old README. The new section names and links the eight pieces (`CLAUDE.md`, `AGENTS.md`, the three `.claude/agents/*.md` role files, `feature_list.json`, the combined `progress/current.md` + `progress/history.md` bullet, and `notes/`) with 1–2 sentences of "what this does" per item. The user picked the dedicated-section framing (β) over a soft mention (α) or a front-loaded everywhere-emphasis (γ); the chosen balance is "strong but not overplayed."

The four-option pitch/diagrams/harness/URLs decision tree was decided up front during planning, with all four recommendations accepted by the user — Hybrid pitch, two Mermaid diagrams, dedicated engineering section, bullets-with-descriptions for the URLs. The implementer surfaced no scope deviations; every link target the spec called out resolved to an existing file (16-of-16 link validation pass). The "181 tests" claim in the highlights block was flagged by the reviewer as a static number that will drift if the suite grows in the future; the user chose to keep it as-is, since the project is closed and the count is accurate.

`./init.sh` is green; the test count is unchanged at 181 (97 unit via Surefire + 84 IT via Failsafe). Mermaid validation is by structural review against the long-stable subset of Mermaid syntax that GitHub's renderer has supported since 2022 (no theme directives, no extension diagram types, no `%%{init}%%`); the implementer flagged that the final visual confirmation requires opening the file's Preview tab on GitHub after push, since local Mermaid previews can render differently than GitHub's.

**This closes the portfolio.** Counts after this entry: 22 done, 0 in_progress, 0 pending. The project ships at: production-deployed on AWS Free Tier under `https://chess-backend.duckdns.org`, frontend on GitHub Pages, 181 tests green through `./init.sh`, full E2E validated in two-browser flow, full doc trail across `docs/architecture.md` + 22 feature notes + `progress/history.md`. Future work mode begins on the next session — no more features in the queue.

**Files touched:**

- `README.md` (modified; full overhaul 197 → 294 lines; structure: title + CI badge, Hybrid pitch, Try-it-live block with 3 URLs + two-browser usage hint, highlights block, architecture Mermaid diagram, end-to-end sequence Mermaid diagram, Stack, Running locally [unchanged 3-workflow section], API [stale "lands with feature 6" line removed; curl examples kept], WebSocket [wscat example kept], Deployment [AWS + Caddy + OIDC kept], Engineering process [new dedicated section linking the 8 harness items], Repository structure tree [expanded to include `.github/`, `infra/`, `notes/`, `progress/`, `docs/`], Out of scope [lifted from `docs/architecture.md`'s closing section: auth, ratings, tournaments, time controls], License)
- `notes/14-readme-polish.md` (new; follows `notes/_template.md`; sections: What we built, Java/Spring concepts [Mermaid as diagram-as-code, README discoverability strategy, GitHub-native rendering], Decisions taken [audit findings, pitch options, diagram count, harness framing, URL formatting], How this compares to what I know [sbt multi-module README discipline + http4s/cats-effect/fs2 + mdoc parallels], Gotchas [GitHub Mermaid version lag, trailing-slash URL convention, escaped-HTML-in-Mermaid footgun], To dig deeper, File map)
- `feature_list.json` (modified: `readme-polish.status` → `done`)
- `progress/current.md` (replaced with the portfolio-closed final note)
- `progress/history.md` (this entry)

**Feature note:** `notes/14-readme-polish.md`.

---

## Portfolio milestone — 2026-05-25

22 features delivered. 0 in_progress. 0 pending. The plan is complete.

The chess-backend-java repo is now a closed portfolio deliverable. The next session is no longer "pick the lowest-priority pending feature" — there are none. Future sessions are bug fixes, observed-in-production tweaks, dependency bumps, or out-of-scope items the user explicitly opens by adding a new entry to `feature_list.json`.

---

## 2026-05-25 — cors-cloudflare-origin (maintenance reopen)

**Status:** done

**Summary:** First feature shipped after the portfolio-closure milestone. The frontend migrated from GitHub Pages to Cloudflare Pages at `https://chess-frontend-52i.pages.dev`, so the backend's CORS allow-list and related tests/docs needed to swap the github.io origin for the new Cloudflare one. Added to `feature_list.json` as priority 15 with feature 11.7 (`cors-x-player-id`) as the precedent for treating a one-line CORS change as a full harness cycle. The decision tree (replace vs keep github.io; feature vs env-var-only vs informal PR) was settled with the user via AskUserQuestion before the plan was drafted: replace github.io entirely, full feature, full harness.

The single-source-of-truth wiring from feature 10 paid off concretely: because REST (`CorsConfig`) and STOMP (`WebSocketConfig`) both read from one `CorsProperties` record, the migration was 1 line in `application.yml`, ~5 lines in `CorsConfigIT` (constant rename + value swap + test-method rename + JavaDoc bullet), 2 lines in `docs/architecture.md`, and 2 lines in `README.md`. No drift surface; no two-place edit possible by construction.

The `GITHUB_PAGES_ORIGIN` constant in `CorsConfigIT` was renamed to `CLOUDFLARE_PAGES_ORIGIN` rather than kept with a new value. Keeping the old name would have lied to the next reader; the rename was trivial and the value of "names that describe what they hold" is cumulative. The two other tests that referenced the constant (the feature-11.7 X-Player-Id regression lock-in and the actual-POST flow test) inherited the new value transparently with no further edits.

The drift canary `preflight_disallowedOrigin_omitsCorsHeaders` stayed intact and green — proving the new default did not accidentally widen the policy. The `evil.example` request is still rejected. `./init.sh` reports 181 tests green (97 unit + 84 IT), unchanged count because this is a value swap, not new behaviour.

**Operator follow-up at deploy time:** after the next push to `main` triggers `deploy.yml`, the user should SSH to EC2 and run `cat /opt/chess/.env | grep CHESS_CORS`. If the env var override is set and still points at github.io, remove or update it; if not set, the new YAML default takes effect automatically.

**Cross-repo:** none required. The frontend already moved; this feature catches the backend up.

**Files touched:**

- `src/main/resources/application.yml` (modified; line 43 default value swap from github.io to Cloudflare)
- `src/test/java/io/github/dariogguillen/chess/config/CorsConfigIT.java` (modified; constant rename `GITHUB_PAGES_ORIGIN` → `CLOUDFLARE_PAGES_ORIGIN`, test method rename `preflight_allowedOriginGithubPages_*` → `preflight_allowedOriginCloudflarePages_*`, class JavaDoc bullet updated, 2 inherited call sites updated automatically; drift canary `preflight_disallowedOrigin_omitsCorsHeaders` untouched)
- `docs/architecture.md` (modified; line 248 bullet "production frontend on GitHub Pages" → "Cloudflare Pages")
- `README.md` (modified; line 9 "Try it live" Frontend URL + description swapped; README:101 source-repo link `github.com/dariogguillen/chess-frontend` deliberately untouched — it's a code-repo URL, not a deploy URL)
- `notes/15-cors-cloudflare-origin.md` (new; follows `_template.md`; gotchas captures the EC2 `/opt/chess/.env` operator follow-up and the feature-11.7 precedent for treating a one-line CORS change as a full harness cycle; "How this compares to what I know" includes the `pureconfig` Scala parallel)
- `feature_list.json` (modified; new entry inserted at priority 15, then flipped to `done`)
- `progress/current.md` (replaced with maintenance-mode session-closed note)
- `progress/history.md` (this entry)

**Feature note:** `notes/15-cors-cloudflare-origin.md`.

---

## Maintenance-mode counts as of 2026-05-25 (after feature 15)

23 features delivered. 0 in_progress. 0 pending. The portfolio remains a closed deliverable; feature 15 was a maintenance reopen via a new entry, not a plan extension. The pattern for the future: any change worth doing in this repo earns its own `feature_list.json` entry and walks the full leader/implementer/reviewer cycle, however small the diff.

---

## 2026-05-27 — auth-core (feature 16, first of the auth bundle)

**Status:** done.

**Summary:** First feature of the post-portfolio-closure auth bundle (16–20). Lands the entire foundation for optional authentication without yet exposing any user-facing auth flow. The user's stated goal: *"seria opcional, se puede seguir juegando sin cuenta, pero con una cuenta se pueden revisar las partidas jugadas por ejemplo"*. Feature 16's contribution is the data model, the Spring Security wiring, and the JWT validator side; feature 17 lands issuance, feature 18 lands OAuth, feature 19 lands `/api/me/games`, feature 20 lands STOMP trust.

The bundle as a whole was planned upfront with five locked decisions (token transport = JWT in `Authorization: Bearer`, OAuth callback = redirect to frontend with JWT in URL fragment, identity linking = fresh start, CORS `allowCredentials` stays false, anonymous STOMP keeps working). Those decisions live in `progress/current.md` and are copy-forwarded into each subsequent feature's plan so reviewers cross-reference one source.

Two-cycle close. The first cycle delivered the spec verbatim, but a leader/user review of the data model surfaced an alternative design that better matched the repo's existing shape. V1's `games` table is deliberately denormalised — `white_player_id` + `white_display_name` + `black_player_id` + `black_display_name` — precisely because adding a `players` row would duplicate the UUID + display name with no extra attached data. The first cycle had created exactly such a `players` table, prepared as a bridge to a future "users → players → games via JOIN" query path. The second cycle replaced it: V2 now creates `users` and adds two nullable FK columns directly on `games` — `white_user_id` and `black_user_id`, each with a partial index `WHERE *_user_id IS NOT NULL`. Feature 19's `GET /api/me/games` query becomes a direct filter on `games` with no join through an intermediate table. The historical `games.{white,black}_player_id` columns remain as audit-time identity snapshots (unconstrained UUIDs, no FK), preserving snapshot semantics: a future rename of `User.display_name` will not rewrite the audit row. The cycle-1 mistake is captured as a portfolio-grade Gotchas entry in the feature note ("comments that foreshadow future migrations are hints, not blueprints").

Spring Security wiring follows the Spring 6 idiom — `SecurityFilterChain` bean (no deprecated `WebSecurityConfigurerAdapter`), stateless `SessionCreationPolicy`, CSRF disabled (safe for header-based JWT), CORS delegates to the existing `CorsConfig`, lambda-style `HttpSecurity` DSL. A custom `JwtAuthenticationFilter` (`OncePerRequestFilter`) reads `Authorization: Bearer <token>`, verifies the HS256 signature via a dedicated `JwtVerifier` wrapping jjwt, loads the `User` by `sub` claim, and sets the `SecurityContext`. All failures are tolerant — the chain continues anonymous and the authorization rules decide whether to 401. The anonymous allow-list explicitly keeps `POST /api/games`, `GET /api/games/{id}`, `/api/games/{id}/moves`, `/actuator/health`, `/v3/api-docs/**`, `/swagger-ui/**`, `/ws/**`, and OPTIONS preflight open for guest play; only `/api/me` (the single new endpoint this feature) is `authenticated()`.

The 401 response shape was settled in cycle 2 alongside the data-model change: `HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)` writes an empty body, and `MeController.@ApiResponse(401)` declares `content = @Content` (empty) so the OpenAPI spec matches the runtime. Adding a structured `AUTHENTICATION_REQUIRED` code to the `ErrorResponse` allow-list is deferred to feature 17 where the issuance endpoints introduce other auth-domain error codes (`EMAIL_ALREADY_TAKEN`, `INVALID_CREDENTIALS`) and the change fits naturally.

`AuthProperties` (`@ConfigurationProperties("auth.jwt")`) binds the secret + expiry from env vars with compact-constructor invariants (secret ≥ 32 bytes for HS256, expiry > 0). Prod has no default for `auth.jwt.secret` — boot fails fast at `BeanCreationException` if `AUTH_JWT_SECRET` is missing. The test profile (`src/test/resources/application-test.yml`) provides a fixed 64-byte secret activated via Surefire + Failsafe `systemPropertyVariables` in `pom.xml`, so every existing IT picks up the test profile without per-class `@ActiveProfiles`.

Test count: 187 (97 unit + 90 IT). Delta +6: `AuthCoreIT` covers missing-header / valid / expired / malformed / wrong-signature on `/api/me`; `BearerCorsIT` pins that a preflight including `Authorization` echoes it in `Access-Control-Allow-Headers`. The 181 existing tests stay green without modification — the implementer confirmed the anonymous game-create / room / WebSocket flows are untouched. `Authorization` was added to `CorsProperties.allowed-headers` (the existing list already had `Content-Type, Accept, X-Player-Id` from feature 11.7).

**Cross-repo:** none required this feature. `/api/me` is unreachable without a JWT and JWT issuance lands in feature 17; the frontend's auth work begins coordinated against that feature, not this one.

**Files touched:**

- `pom.xml` (modified; adds `spring-boot-starter-security` + jjwt-api/impl/jackson 0.12.6 with inline POM justification comments; Surefire+Failsafe `systemPropertyVariables` activate `spring.profiles.active=test` so every IT picks up `src/test/resources/application-test.yml`)
- `src/main/resources/application.yml` (modified; `auth.jwt.secret: ${AUTH_JWT_SECRET:}` fail-fast prod default; `auth.jwt.expiry-seconds: 604800`)
- `src/main/resources/db/migration/V2__create_users_and_game_user_links.sql` (new in cycle 2, replacing the cycle-1 `V2__create_users_and_player_user_link.sql` which was deleted from disk before any commit; creates `users` table — UUID PK, email VARCHAR(254) NOT NULL UNIQUE per RFC 5321, display_name VARCHAR(100), password_hash VARCHAR(60) per BCrypt, google_sub VARCHAR(255) per Google `sub` upper bound, created_at TIMESTAMPTZ NOT NULL DEFAULT now() — partial unique index on `google_sub WHERE google_sub IS NOT NULL`, and adds two nullable FK columns to `games`: `white_user_id` and `black_user_id` each with a partial index `WHERE *_user_id IS NOT NULL`)
- `src/main/java/io/github/dariogguillen/chess/domain/User.java` (new; JPA entity with column-length annotations matching the migration caps; mutable on purpose with package-private setters; never returned through a controller)
- `src/main/java/io/github/dariogguillen/chess/persistence/UserRepository.java` (new; `JpaRepository<User, UUID>` with `findByEmail` and `findByGoogleSub`)
- `src/main/java/io/github/dariogguillen/chess/config/AuthProperties.java` (new; `@ConfigurationProperties("auth.jwt")` record with compact-constructor invariants)
- `src/main/java/io/github/dariogguillen/chess/config/SecurityConfig.java` (new; `SecurityFilterChain` bean, stateless, CSRF off, anonymous allow-list, `HttpStatusEntryPoint(401)`, `BCryptPasswordEncoder` bean)
- `src/main/java/io/github/dariogguillen/chess/config/security/JwtVerifier.java` (new; jjwt wrapper holding the HS256 `SecretKey`)
- `src/main/java/io/github/dariogguillen/chess/config/security/JwtAuthenticationFilter.java` (new; `OncePerRequestFilter`, tolerant of all failures, leaves chain anonymous on bad/missing token)
- `src/main/java/io/github/dariogguillen/chess/web/auth/MeController.java` (new; `GET /api/me` with springdoc annotations; cycle 2 changed the 401 `@ApiResponse` to declare `content = @Content` empty so the spec matches the runtime)
- `src/main/java/io/github/dariogguillen/chess/web/auth/MeResponse.java` (new; DTO record)
- `src/main/java/io/github/dariogguillen/chess/config/CorsConfig.java` (modified; adds `Authorization` to `allowedHeaders`)
- `src/test/resources/application-test.yml` (new; fixed 64-byte HS256 test secret)
- `src/test/java/io/github/dariogguillen/chess/config/security/AuthCoreIT.java` (new; 5 cases — missing-header, valid JWT, expired, malformed, wrong-signature)
- `src/test/java/io/github/dariogguillen/chess/config/BearerCorsIT.java` (new; preflight pin for `Authorization`)
- `docs/architecture.md` (modified; new "Authentication" section between API contract and CORS, documents the bundle scope, User aggregate, JWT model, fresh-start identity, two-FK-on-games design, and audit-snapshot retention of `games.{white,black}_player_id`; cycle 2 rewrote this section)
- `notes/16-auth-core.md` (new; follows `_template.md`; Decisions taken describes the cycle-2 data-model resolution, the `HttpStatusEntryPoint` vs custom entry point decision, Surefire profile activation; Gotchas captures the cycle-1 mistake as a portfolio-grade design lesson; "How this compares to what I know" covers tsec / http4s middleware / pureconfig / jjwt parallels)
- `feature_list.json` (new entries 16–20 added for the auth bundle; `auth-core` flipped to done after user OK)
- `progress/current.md` (rewritten with the bundle plan + feature-16 detail; will be rewritten again with feature-17 detail at this entry's close)
- `progress/history.md` (this entry)

**Feature note:** `notes/16-auth-core.md`.

---

## 2026-05-27 — auth-jwt (feature 17, second of the auth bundle)

**Status:** done.

**Summary:** Second feature of the auth bundle. Lands the email/password issuance side that feature 16 deliberately left out, locks the JWT shape for the rest of the bundle, and closes the 401 spec/runtime gap that feature 16's review surfaced as out-of-scope. Two new endpoints — `POST /api/auth/register` and `POST /api/auth/login` — both returning `AuthResponse(token, MeResponse user)`. The `MeResponse` shape is reused from feature 16 so the frontend sees a uniform "current user" payload across `/api/me`, register 201, and login 200.

The 401 entry-point swap is the most architecturally consequential part. Feature 16 closed with `HttpStatusEntryPoint(401)` writing an empty body, and `MeController.@ApiResponse(401)` declaring `content = @Content` (empty) so the OpenAPI spec did not lie. Feature 17 introduces a custom `AuthEntryPoint` that writes a structured `ErrorResponse(error = AUTHENTICATION_REQUIRED, message, timestamp)` JSON body using the same `Clock` + `ObjectMapper` shape `GlobalExceptionHandler.build` uses, the `SecurityConfig.exceptionHandling` block now wires `AuthEntryPoint` in place of `HttpStatusEntryPoint`, and `MeController` restores its 401 `@ApiResponse` to reference `ErrorResponse`. Spec and runtime now agree on a non-empty body. `AuthCoreIT.me_withoutAuthHeader_returns401WithAuthenticationRequiredBody` (renamed from the feature-16 name) asserts the new shape; the other four `AuthCoreIT` cases stay byte-identical.

Three new error codes joined the `ErrorResponse.@Schema(allowableValues)` enum: `AUTHENTICATION_REQUIRED`, `EMAIL_ALREADY_TAKEN`, `INVALID_CREDENTIALS`. Total grew from 9 to 12. The `OpenApiIT` drift canary that pins this list was updated, not deleted — renamed to `errorResponseSchema_listsExactlyTheTwelveKnownErrorCodes` to keep the name honest with the count.

Security defences. `AuthService.authenticate` runs a constant-time login failure path: a `static final` BCrypt dummy hash is matched against the supplied password on the unknown-email branch so the response time matches the wrong-password branch (defence against timing-based user enumeration). The dummy is verified not to collide with `password`, `admin`, `12345678`, or other well-known passwords. A defence-in-depth `user == null` check provides a second safety net. Both failure modes return a 401 with byte-identical `error` and `message` fields (`INVALID_CREDENTIALS` and `"Invalid email or password"`) — `AuthEndpointsIT.login_unknownEmail_returnsSameBodyAsWrongPassword` asserts this equality. `AuthService.register` runs under `@Transactional` and catches `DataIntegrityViolationException` from the DB `UNIQUE` constraint as the race-window safety net, translating it to the same `EmailAlreadyTakenException` that the eager `findByEmail` check throws (one 409 + one code regardless of branch). No password value is logged anywhere. `RegisterRequest.password` declares `@Size(min = 8, max = 72)` to enforce the BCrypt input cap; `LoginRequest.password` deliberately does NOT enforce `@Size` so a wrong-length attempt produces a 401 rather than a 400 (would leak the length policy).

The `JwtIssuer` counterpart to `JwtVerifier` follows the simplest correct shape: both classes independently call `Keys.hmacShaKeyFor(props.secret().getBytes(StandardCharsets.UTF_8))` on the same `AuthProperties` bean, producing byte-identical `SecretKey`s without any inter-class coordination. The round-trip IT case `AuthEndpointsIT.roundTrip_registerThenLogin_thenMeReturnsSameUser` pins the symmetry end-to-end: register → take token from response → login → take token from response → call `/api/me` with token → assert user payload. `JwtIssuer` injects the existing application `Clock` bean from `ClockConfig`, so a future test that swaps the clock for a fixed instant can do so without touching the issuer. The feature-16 `AuthCoreIT.me_withExpiredJwt_returns401` regression keeps working because it pre-mints its expired token via the jjwt API directly rather than via `JwtIssuer` — independent of `JwtIssuer`'s clock.

Exception wiring stayed minimal. `EmailAlreadyTakenException` extends the existing `ConflictException` and `GlobalExceptionHandler.codeOf` already derived the upper-snake-case `EMAIL_ALREADY_TAKEN` code from the simple class name with no need to override anything. `InvalidCredentialsException` extends `ChessException` directly — adding an `UnauthorizedException` umbrella for a single 401 would be over-engineering. A new `@ExceptionHandler(InvalidCredentialsException.class)` method in `GlobalExceptionHandler` maps the exception to 401 with code `INVALID_CREDENTIALS`. `AuthService` consumes the web records (`RegisterRequest`, `LoginRequest`) directly instead of introducing parallel `RegisterCommand` / `LoginCommand`, matching the existing service-layer style (`RoomService.joinRoom(String, String)`).

Test count: 196 (97 unit + 99 IT). Delta +9 IT, +0 unit. All nine cases live in `AuthEndpointsIT`: happy register, dup-email 409, three validation-error 400s (invalid email, weak password, missing displayName), happy login, wrong-password 401, unknown-email 401 (with the byte-equal-body assertion against wrong-password), and the round-trip case. No unit tests added — `JwtIssuer` is a single-line wrapper covered by the round-trip case; validation is exercised at the IT level. `OpenApiIT.apiDocs_includesOperationSummaries` stayed green confirming both new endpoints declare `@Operation(summary = ...)`.

**Cross-repo:** required (additive). The two new endpoints become public surface; the frontend's auth UI feature begins coordinating against this shape. The `docs/architecture.md` Authentication section gained the issuance flow + 3 new codes; the API contract section added the endpoints. `README.md` got a small "Authentication (optional)" subsection linking to Swagger UI, and the static test-count claim was updated from 181 directly to 196 (one of the three 2026-05-25 operator follow-ups partially closes here).

**Files touched:**

- `src/main/java/io/github/dariogguillen/chess/config/security/JwtIssuer.java` (new; jjwt builder wrapper sharing key derivation with feature-16's JwtVerifier)
- `src/main/java/io/github/dariogguillen/chess/config/security/AuthEntryPoint.java` (new; custom AuthenticationEntryPoint writing the structured 401 body)
- `src/main/java/io/github/dariogguillen/chess/service/auth/AuthService.java` (new; new `service/auth/` package; `@Transactional register` + constant-time `authenticate`; DataIntegrityViolationException race safety net)
- `src/main/java/io/github/dariogguillen/chess/web/auth/AuthController.java` (new; class-level `@Tag(name = "Authentication", ...)`, both endpoints fully springdoc-annotated)
- `src/main/java/io/github/dariogguillen/chess/web/auth/RegisterRequest.java` (new; `@Email @NotBlank @Size` record; 72-cap on password documented inline)
- `src/main/java/io/github/dariogguillen/chess/web/auth/LoginRequest.java` (new; no `@Size` on password, deliberate)
- `src/main/java/io/github/dariogguillen/chess/web/auth/AuthResponse.java` (new; `(String token, MeResponse user)` record reusing feature-16's MeResponse)
- `src/main/java/io/github/dariogguillen/chess/exception/EmailAlreadyTakenException.java` (new; extends ConflictException; `codeOf` derives `EMAIL_ALREADY_TAKEN`)
- `src/main/java/io/github/dariogguillen/chess/exception/InvalidCredentialsException.java` (new; extends ChessException; constant message)
- `src/main/java/io/github/dariogguillen/chess/exception/ErrorResponse.java` (modified; `@Schema(allowableValues)` grows 9 → 12)
- `src/main/java/io/github/dariogguillen/chess/exception/GlobalExceptionHandler.java` (modified; new handleInvalidCredentials → 401)
- `src/main/java/io/github/dariogguillen/chess/config/SecurityConfig.java` (modified; `/api/auth/register` + `/api/auth/login` added to anonymous allow-list; `HttpStatusEntryPoint` swapped for `AuthEntryPoint`)
- `src/main/java/io/github/dariogguillen/chess/web/auth/MeController.java` (modified; 401 `@ApiResponse` restored to reference `ErrorResponse` schema)
- `src/test/java/io/github/dariogguillen/chess/web/auth/AuthEndpointsIT.java` (new; new `test/web/auth/` package; 9 cases)
- `src/test/java/io/github/dariogguillen/chess/config/security/AuthCoreIT.java` (modified; missing-header case renamed + asserts the structured body; other 4 cases unchanged)
- `src/test/java/io/github/dariogguillen/chess/config/OpenApiIT.java` (modified; drift canary updated for 12-code list; method renamed)
- `docs/architecture.md` (modified; Authentication section extended with the issuance flow + new 401 entry point; API contract section adds the endpoints + 3 new error codes)
- `README.md` (modified; new "Authentication (optional)" subsection in the API section linking to Swagger UI; static test-count claim bumped 181 → 196)
- `notes/17-auth-jwt.md` (new; follows `_template.md`; cross-ecosystem section covers tsec JWT round-trip / http4s AuthMiddleware / doobie ConnectionIO vs `@Transactional` / `pdi/jwt` / argon2 vs BCrypt; Decisions section captures the 6 implementer decisions including the constant-time login defence; Gotchas covers the 72-byte BCrypt cap, dummy-hash collision-avoidance, and the user-enumeration uniformity rule)
- `feature_list.json` (modified: `auth-jwt.status` → `done`; `auth-google-oauth.status` → `in_progress`)
- `progress/current.md` (rewritten with feature-18 detail at this entry's close)
- `progress/history.md` (this entry)

**Feature note:** `notes/17-auth-jwt.md`.

---

## 2026-05-27 — auth-google-oauth (feature 18, third of the auth bundle)

**Status:** done.

**Summary:** Third feature of the auth bundle. Lands Google OAuth 2.0 sign-in alongside the email/password path from feature 17. The two paths converge on the same `User` aggregate, mint the same JWT shape via the same `JwtIssuer`, and reach the rest of the application through the same `JwtAuthenticationFilter`. From the frontend's perspective the choice is "click button A or button B" — once authenticated, everything downstream is identical.

The architectural choice was Spring Security's `oauth2Login` DSL plus a single custom `AuthenticationSuccessHandler`. Spring Security's `CommonOAuth2Provider.GOOGLE` constant carries Google's OAuth endpoints (authorization, token, userinfo), the framework auto-registers `/oauth2/authorization/google` (initiates the dance) and `/login/oauth2/code/google` (handles the callback), and we wrote ~150 lines of `OAuth2SuccessHandler` to translate Google's `OAuth2User` into our `User` + `JwtIssuer.issue` + a redirect to the frontend with the token in the URL fragment. The framework owned the OAuth state, the PKCE-ish session, the code-for-token exchange, the userinfo fetch, and the principal construction; we owned the find-or-create-user, JWT mint, and redirect.

Token delivery uses the URL **fragment** (`#token=<jwt>`), not a query parameter. This is the deliberate log-hygiene choice from the bundle planning: fragments are not sent to the server in subsequent requests, so even if an intermediate proxy or CDN logs the redirect URL, the token is not part of what it captures. The frontend reads `window.location.hash`, strips `#token=`, stores the JWT, and calls `history.replaceState` to clean the URL — feature scope on the `chess-frontend` repo.

Email collision policy. The bundle's locked decision was "no account linking" — Google + email/password in the same User is out of scope. When an OAuth callback arrives for an email that's already registered under email/password (no `googleSub` on the existing User), the handler does not merge identities and does not throw. It redirects to `${frontend-redirect-base}/auth/callback#error=email_taken`. The frontend reads the fragment and shows a user-facing message; the backend logs a generic warning (no PII). This is feature 18's contribution to the bundle's user-enumeration / account-takeover defenses — silently merging would let a Google account take over an email/password user just by knowing the email.

`User.passwordHash = null` for OAuth-only users. Not an empty string. BCrypt would happily hash an empty string and create a phantom-loginable user — the password input `""` would `matches()` against the stored empty-string hash. The IT explicitly asserts `getPasswordHash()` is null with an in-message rationale so a future implementer cannot accidentally regress this to a sentinel value.

`AuthProperties` was restructured during implementation. The plan asked for a nested `OAuthProps` record alongside the existing JWT properties, but the existing bean was bound to `@ConfigurationProperties("auth.jwt")` — adding `auth.oauth.frontend-redirect-base` would have required either a separate `OAuth2Properties` class (split surface) or this restructure (single source). The implementer chose the latter: `AuthProperties` now binds to `@ConfigurationProperties("auth")` with nested records `Jwt(secret, expirySeconds)` and `OAuthProps(frontendRedirectBase)`. Convenience accessors `secret()` and `expirySeconds()` on the outer class delegate to `Jwt` so `JwtIssuer`, `JwtVerifier`, `AuthCoreIT`, and `AuthEndpointsIT` compile unchanged. Spring's relaxed binding maps the unchanged YAML keys (`auth.jwt.secret`, `auth.jwt.expiry-seconds`) onto the nested record automatically.

Defensive parsing on the OAuth principal. `sub`, `email`, and `name` are all checked for null and blank. The `Authentication` itself is checked for `OAuth2AuthenticationToken` via `instanceof` so a non-OAuth principal arriving at the handler (a configuration error) produces a generic warning + error redirect rather than a NPE 500. The "missing profile" path tests this with a `DefaultOAuth2User` constructed without an email.

Test count: 200 (97 unit + 103 IT). Delta +4 IT, +0 unit. All four IT cases live in `OAuth2SuccessHandlerIT`:
- happy path with a fresh Google `sub` — round-trip asserts `#token=` value calling `/api/me` works (proves JWT interchangeability with `/api/auth/login`).
- pre-existing User with matching `googleSub` — reuse (count unchanged, same user returned).
- email collision with email/password user — redirect-with-error (count unchanged, no merge).
- missing email — redirect-with-error.

The IT autowires the production `OAuth2SuccessHandler` bean and invokes it with mock servlet objects, exercising the actual Spring-managed wiring rather than a unit-instantiated handler bypassing the dependency graph.

**Cross-repo:** required (additive). Two new endpoints are public: `/oauth2/authorization/google` initiates the flow, `/login/oauth2/code/google` handles the callback. Neither carries a JSON body or REST contract — they are Spring-managed. The frontend's auth UI adds a "Sign in with Google" button pointing at the first; the existing `/auth/callback` route (already used for the JWT fragment) handles both feature 17 and feature 18 outputs identically.

**Operator follow-ups added by this feature** (real-world steps the user must do before production works):
- Create OAuth 2.0 Client ID in Google Cloud Console (Web application type).
- Authorised redirect URIs: `https://chess-backend.duckdns.org/login/oauth2/code/google` AND `http://localhost:8080/login/oauth2/code/google` (for local dev).
- Set env vars in `/opt/chess/.env` on EC2: `GOOGLE_OAUTH_CLIENT_ID`, `GOOGLE_OAUTH_CLIENT_SECRET`, `AUTH_OAUTH_FRONTEND_REDIRECT_BASE=https://chess-frontend-52i.pages.dev`. Documented in `notes/18-auth-google-oauth.md` "Operator follow-ups" and `docs/architecture.md` "Operator setup".

**Files touched:**

- `pom.xml` (modified; adds `spring-boot-starter-oauth2-client` with inline justification — Spring Security's Google client + token exchange + userinfo fetch; NO `oauth2-resource-server` because we are a client, not a resource server)
- `src/main/java/io/github/dariogguillen/chess/config/AuthProperties.java` (modified; restructured to `@ConfigurationProperties("auth")` with nested records `Jwt(secret, expirySeconds)` and `OAuthProps(frontendRedirectBase)`; convenience accessors `secret()` and `expirySeconds()` preserve the feature-16/17 API surface; both inner records have compact-constructor invariants — HS256 secret ≥ 32 bytes, expiry > 0, frontend-redirect-base non-blank)
- `src/main/java/io/github/dariogguillen/chess/config/SecurityConfig.java` (modified; added `.oauth2Login(oauth -> oauth.successHandler(oAuth2SuccessHandler))` to the `SecurityFilterChain`, extended the anonymous allow-list with `/oauth2/**` and `/login/oauth2/**`, constructor-injected `OAuth2SuccessHandler`)
- `src/main/java/io/github/dariogguillen/chess/config/security/OAuth2SuccessHandler.java` (new; implements `AuthenticationSuccessHandler`; defensive parsing of `OAuth2User`; find-or-create User by `googleSub`; email-collision redirects with `#error=email_taken`; missing-profile redirects with `#error=oauth_missing_profile`; happy path mints JWT via `JwtIssuer.issue(user)` and redirects with `#token=`; three `log.warn(...)` lines all generic, zero PII)
- `src/main/resources/application.yml` (modified; `spring.security.oauth2.client.registration.google` block with `client-id`, `client-secret`, `scope: openid, email, profile`, `redirect-uri: '{baseUrl}/login/oauth2/code/google'` template that resolves at runtime; `auth.oauth.frontend-redirect-base: ${AUTH_OAUTH_FRONTEND_REDIRECT_BASE:http://localhost:5173}` with the dev Vite default)
- `src/test/resources/application-test.yml` (modified; provides fake `test-client-id` / `test-client-secret` and `auth.oauth.frontend-redirect-base: http://localhost:5173` so the context loads for every IT; the actual OAuth flow is never triggered against Google in tests)
- `src/test/java/io/github/dariogguillen/chess/config/security/OAuth2SuccessHandlerIT.java` (new; 4 IT cases per the plan — happy path, existing-sub-reuses, email-taken-redirect, missing-email-redirect; happy path asserts JWT interchangeability via round-trip to `/api/me`; direct handler invocation via autowired bean with mock servlet objects)
- `docs/architecture.md` (modified; new "Google OAuth 2.0 sign-in (feature 18)" subsection with a Mermaid `sequenceDiagram` of the flow Frontend → Backend → Google → Backend → Frontend; "Identity-collision policy" subsection documenting the `email_taken` redirect; "URL-fragment delivery" subsection explaining why fragment not query; "Operator setup" subsection covering Google Cloud Console steps and EC2 env vars)
- `README.md` (modified; "Authentication (optional)" subsection gains a "Sign in with Google" bullet pointing at `/oauth2/authorization/google`; static test-count claim bumped 196 → 200)
- `notes/18-auth-google-oauth.md` (new; follows `_template.md`; cross-ecosystem section covers Scala — http4s + silhouette + pac4j-scala — and Node — passport-google-oauth20 + Auth.js; Decisions captures (a) email-taken redirect not exception, (b) `passwordHash = null` not empty, (c) IT direct invocation, (d) `AuthProperties` restructure rationale; Gotchas covers log hygiene, allow-list dance, fragment-vs-query, BCrypt-on-empty-string footgun)
- `feature_list.json` (modified: `auth-google-oauth.status` → `done`; `auth-my-games.status` → `in_progress`)
- `progress/current.md` (rewritten with feature-19 detail at this entry's close)
- `progress/history.md` (this entry)

**Feature note:** `notes/18-auth-google-oauth.md`.

---

## 2026-05-28 — auth-my-games (feature 19, fourth of the auth bundle)

**Status:** done.

**Summary:** Fourth feature of the auth bundle. Lands the actual product benefit the user named when opening the bundle ("con una cuenta se pueden revisar las partidas jugadas"): a new authenticated `GET /api/me/games?page=&size=` endpoint and the write surface that makes it meaningful — `games.white_user_id` / `games.black_user_id` (the FK columns feature 16 added) are now populated whenever the corresponding side was authenticated at game-creation time.

Two surfaces moved together. The read surface is a separate controller and endpoint from the existing guest-friendly `GET /api/players/{id}/games` — guests can still inspect their own history via the X-Player-Id path, authenticated users get their own history view bound to their stable `User.id` instead. The new path is `.authenticated()` in `SecurityConfig` (no permitAll match), the old path stays open. The pagination contract follows Spring Data's standard `Page<T>` JSON envelope (`content`, `totalElements`, `totalPages`, `number`, `size`) so the frontend can consume it via standard typed-client codegen with zero custom shape work.

The write surface required threading the user identity from the security context all the way through the active-state Redis representation to the eventual `GameEntity` archive write. `Player` (the domain record) gained a nullable `UUID userId` field; the compact constructor explicitly does NOT `requireNonNull` it. `RoomService.createRoom` and `joinRoom` now take an optional `currentUserId` parameter. `RoomController` reads `@AuthenticationPrincipal User currentUser` (null for guests) and threads its id; `GameController` does the same. `GameEntityMapper` propagates `Player.userId()` to the new `GameEntity` columns on archive. For guest games, both FK columns stay null and the existing flow is byte-identical to feature 5/9's behaviour.

The biggest design call was wire-format isolation. `Player.userId` MUST NOT leak through REST responses or STOMP events — leaking it would expose internal identity to opponents and viewers, breaking the bundle's stated identity model. The implementer chose a `PlayerView(UUID id, String displayName)` record approach: `GameStateResponse` now embeds `PlayerView` instead of `Player`, and `RoomJoinedEvent` defines its own nested `PlayerView` for the STOMP wire. Both refactors preserved accessor-call compatibility (`view.id()` / `view.displayName()`) so existing tests like `RoomLifecycleIT` stayed green without modification. A grep across `web/` and `websocket/` confirmed no DTO/event component named `userId` is exposed to any wire format; the `OpenApiIT` spec inspection corroborated.

Jackson backwards-compatibility for the new `Player.userId` field was deliberately verified. The Redis active state holds serialised `Game` records (with `Player` inside); games in-flight at deploy time have JSON without `userId`. Jackson's default behaviour selects the canonical 3-arg constructor (by component-count match) and supplies null for the missing field — but only if the canonical constructor accepts null. The 2-arg convenience constructor added for source compatibility is deliberately NOT marked `@JsonCreator` (which would override the canonical selection). The implementer documented this in the feature note's Gotchas section as a portfolio-grade lesson about Jackson record creator selection.

Self-side determination in `MyGamesController` extends `ArchivedGamePlayerView` with `whiteUserId` / `blackUserId` fields so the controller can do the `view.whiteUserId().equals(currentUserId)` comparison and pick `opponentDisplayName`. Same pattern as `PlayerGamesController.toSummary` — code consistency over cleverness. The existing repository IT stayed green with a one-line constructor-signature drift (`null, null` for the new FK args on the test helper) and the existing `PlayerGamesControllerIT` stayed green unchanged thanks to projection-extension backwards compatibility.

The `@Min`/`@Max` annotations on `@RequestParam` page/size triggered a new failure mode that pre-feature-19 controllers did not hit: Spring 6.1's `HandlerMethodValidationException` for parameter validation (and the legacy `ConstraintViolationException` from `@Validated`). Two new `@ExceptionHandler` methods in `GlobalExceptionHandler` map both to 400 `VALIDATION_FAILED` with the existing `ErrorResponse` shape. No new error code introduced — the 12-code allowlist from feature 17 is intact and the `OpenApiIT` drift canary stayed green.

Test count: 207 (97 unit + 110 IT). Delta +7 IT, +0 unit. All cases in `MyGamesIT`: 401 without auth, empty history, A-sees-only-own, anonymous-games-not-visible-to-A, pagination (5 games × `?size=2`), invalid pagination (size=101, page=-1), and authenticated game creation populating the FK columns. The IT added an `@AfterEach cleanUpGames` so cross-IT cleanup ordering (child rows before `users.deleteAll()`) doesn't trip the FK constraint when `MyGamesIT` runs before `AuthEndpointsIT`. Spring's "Serializing PageImpl instances as-is is not supported" warning was logged during the IT run — wire shape is correct today, but a future Spring Data upgrade may change `PageImpl` serialisation; an explicit `MyGamesPage` schema record documents the OpenAPI shape and could become the runtime envelope via `@EnableSpringDataWebSupport(pageSerializationMode = VIA_DTO)` in a future tidy-up. Non-blocking observation, captured in the reviewer report.

**Cross-repo:** required (additive). The `/api/me/games` endpoint is the frontend's "my games" view. The pagination JSON shape is the standard Spring envelope so the typed client codegen via `/v3/api-docs` is unsurprised. The existing `/api/players/{id}/games` stays unchanged so the frontend can switch endpoints based on auth state.

**Files touched:**

- `src/main/java/io/github/dariogguillen/chess/domain/Player.java` (modified; added nullable `UUID userId` + 2-arg convenience constructor; compact constructor accepts null explicitly; JavaDoc explains the field semantics)
- `src/main/java/io/github/dariogguillen/chess/persistence/GameEntity.java` (modified; added `whiteUserId` and `blackUserId` UUID columns mapped to the V2 migration FKs)
- `src/main/java/io/github/dariogguillen/chess/persistence/GameEntityMapper.java` (modified; propagates `Player.userId()` both directions across the archive boundary)
- `src/main/java/io/github/dariogguillen/chess/persistence/GameHistoryRepository.java` (modified; new `findByUserId(UUID, Pageable) → Page<ArchivedGamePlayerView>` JPQL query; existing `findByPlayerId` SELECT list extended to include the two new FK fields)
- `src/main/java/io/github/dariogguillen/chess/persistence/ArchivedGamePlayerView.java` (modified; added `whiteUserId` and `blackUserId` fields for self-side determination)
- `src/main/java/io/github/dariogguillen/chess/service/GameHistoryService.java` (modified; new `findByUser(UUID, Pageable)` method)
- `src/main/java/io/github/dariogguillen/chess/service/RoomService.java` (modified; `createRoom` and `joinRoom` take optional `UUID currentUserId`)
- `src/main/java/io/github/dariogguillen/chess/web/room/RoomController.java` (modified; reads `@AuthenticationPrincipal User` and threads its id)
- `src/main/java/io/github/dariogguillen/chess/web/game/GameStateResponse.java` (modified; refactored to embed `PlayerView(id, displayName)` records instead of raw `Player` — wire-format isolation)
- `src/main/java/io/github/dariogguillen/chess/web/game/GameController.java` (modified; added `toPlayerView` boundary helper)
- `src/main/java/io/github/dariogguillen/chess/websocket/RoomJoinedEvent.java` (modified; `blackPlayer` field now `RoomJoinedEvent.PlayerView` for STOMP wire-format isolation)
- `src/main/java/io/github/dariogguillen/chess/exception/GlobalExceptionHandler.java` (modified; new `@ExceptionHandler` for `HandlerMethodValidationException` and `ConstraintViolationException`, both → 400 `VALIDATION_FAILED`)
- `src/main/java/io/github/dariogguillen/chess/web/me/MyGamesController.java` (new; new `web/me/` package; `GET /api/me/games?page=&size=` with full springdoc, `@AuthenticationPrincipal`, `@Validated @Min/@Max`)
- `src/main/java/io/github/dariogguillen/chess/web/me/MyGameSummary.java` (new; response item record)
- `src/test/java/io/github/dariogguillen/chess/web/me/MyGamesIT.java` (new; new test package; 7 cases including write-surface verification via case 7)
- `src/test/java/io/github/dariogguillen/chess/persistence/GameHistoryRepositoryIT.java` (modified; test helper `newGameEntity` passes `null, null` for the two new FK args — pure structural drift, no behavioural change)
- `docs/architecture.md` (modified; new "Per-user game history (feature 19)" subsection under Authentication; FK columns now described as active)
- `README.md` (modified; new endpoint bullet under Authentication; static test-count claim bumped 200 → 207)
- `notes/19-auth-my-games.md` (new; follows `_template.md`; Decisions captures the 5 deferred decisions + GlobalExceptionHandler structural extension; Gotchas covers Spring 6.1 validation exception change, Jackson canonical-constructor selection, `GameEntity` constructor drift, FK constraint trip during cross-IT cleanup; cross-ecosystem section covers doobie pagination, http4s `AuthedRoutes` parallel for `@AuthenticationPrincipal`, `Page<T>` vs `cats.data.NonEmptyList`)
- `feature_list.json` (modified: `auth-my-games.status` → `done`; `auth-stomp-trust.status` → `in_progress`)
- `progress/current.md` (rewritten with feature-20 detail at this entry's close)
- `progress/history.md` (this entry)

**Feature note:** `notes/19-auth-my-games.md`.

---

## 2026-05-29 — auth-stomp-trust (feature 20, last of the auth bundle)

**Status:** done.

**Summary:** Fifth and final feature of the auth bundle. Closes the last gap left by features 16–19: REST is auth-aware but the WebSocket / STOMP surface still trusts whatever `playerId` the client sends in a frame. After this feature, an authenticated session cannot claim to be another user's player and an anonymous session cannot mid-stream switch to a different player identity. Anonymous WebSocket use stays first-class — bundle decision 7 is preserved verbatim.

The whole feature is a single `ChannelInterceptor` registered on `clientInboundChannel`. Two phases. **Phase 1 (CONNECT)** inspects the STOMP CONNECT frame's native `Authorization` header. Present and valid → load the User via `JwtVerifier` + `UserRepository`, attach via `StompHeaderAccessor.setUser(...)` and on `SimpAttributes`. Present and invalid (expired, malformed, wrong signature) → DEBUG log (not WARN — first-deploy day will see this for every still-old-frontend session as routine traffic), session stays anonymous. Absent → session stays anonymous. The interceptor NEVER rejects a CONNECT for identity reasons; that would deadlock guest play. **Phase 2 (SEND / SUBSCRIBE)** validates any explicit `playerId` claim against the session identity. Authenticated sessions: the claimed player must belong to a `Player` row whose `userId` equals the session's `User.id`; destination-based lookup (`/topic/games/{gameId}` or `/app/games/{gameId}/...`) pulls the game via `GameStore` to do the check. Anonymous sessions: pin-on-first-use — the first `playerId` an anonymous session uses is recorded on `SimpAttributes`, subsequent SENDs with a different `playerId` are rejected. Frames without a `playerId` claim (spectator subscriptions, pure SUBSCRIBE on broadcast topics) pass through unchanged.

Spoof attempts produce an ERROR frame back through `clientOutboundChannel` and the message is dropped (`preSend` returns null). The session stays connected. A buggy client can correct itself and retry. NEVER force-disconnect — that would interact badly with feature 11's grace-period reconnect layer.

The implementation surfaced a bean-graph cycle the plan did not anticipate: `WebSocketConfig` → `StompAuthInterceptor` → `GameService` → `SimpMessagingTemplate` → `WebSocketConfig`. The implementer resolved it without touching the application layer by switching the game-lookup dependency from `GameService` to `GameStore` (the lower-level seam `GameService.findById` itself reads — behaviourally identical for the read path) and `@Lazy`-injecting `@Qualifier("clientOutboundChannel") MessageChannel` for the ERROR-frame send-back (the proxy is invoked only after the broker is up, by which time the bean graph is fully wired). This is documented in `notes/20-auth-stomp-trust.md` Decisions section as the most portfolio-grade lesson of the feature: in Spring, the bean-cycle workaround is rarely about restructuring the application — usually about choosing a lower-level seam or a `@Lazy` proxy at the right injection point.

A second adaptation was the pin-on-first-use fallback. The plan's anonymous spoof check referenced an "X-Player-Id session attribute" anchor, but inspection revealed the anchor doesn't exist — the X-Player-Id flow is REST-only, not propagated into STOMP sessions. Pin-on-first-use is the minimum-state delivery of the plan's case 5 ("anonymous session cannot switch player identity") without scope creep into `RoomService`, the WebSocket handshake, or feature 11.7's surface. Same per-session storage via `SimpAttributes` keeps the isolation property: one session's first-use cannot poison another.

Two-cycle close. Cycle 1 landed everything correctly but reported the test counts wrong (claimed 209 = 97 + 112, actual was 212 = 97 + 115). Reviewer rejected with a single specific issue: `README.md` test count claim and the note's count-delta line both stale. Cycle 2 fixed both text edits plus a bonus cleanup the reviewer had flagged as out-of-scope (two fully-qualified Spring class names in `StompAuthIT.java` — `WebSocketHttpHeaders` and `StompCommand` — replaced with imports + bare simple names). Final test count: 212 (97 unit + 115 IT). Delta +5 IT, 0 unit.

The anonymous regression is the most important checkpoint: 18 existing WebSocket-IT cases across `RoomLifecycleIT` (4), `GameWebSocketIT` (5), `ViewerCountIT` (5), `DisconnectHandlingIT` (3), `DisconnectNotificationsIT` (3) all stayed green WITHOUT MODIFICATION. The reviewer ran `git diff --name-only src/test` and confirmed only `StompAuthIT.java` (new) is the test-side delta. Bundle decision 7 holds end-to-end.

**Cross-repo:** optional / additive. The frontend can OPTIONALLY start attaching `Authorization: Bearer <jwt>` to its STOMP CONNECT to identify the session. Without it, the existing X-Player-Id flow continues working. So the frontend adopts this at its own pace; the backend ships independently.

**Files touched:**

- `src/main/java/io/github/dariogguillen/chess/websocket/StompAuthInterceptor.java` (new; `ChannelInterceptor` implementation; constructor-injected `JwtVerifier`, `UserRepository`, `GameStore`, and `@Lazy @Qualifier("clientOutboundChannel") MessageChannel`; `GAME_DESTINATION_PATTERN` regex covers `/topic/games/{id}` and `/app/games/{id}/...`; pin-on-first-use stored on `SimpAttributes` under `SESSION_PINNED_PLAYER_ID_ATTR`; 4 generic log lines with no PII; ERROR frame sent back through outbound channel + return null from preSend to drop the message)
- `src/main/java/io/github/dariogguillen/chess/config/WebSocketConfig.java` (modified; `configureClientInboundChannel` registers `StompAuthInterceptor` as the single interceptor on the inbound channel; existing `PlayerSessionTracker` and `ViewerCountTracker` remain as `@EventListener` beans firing after the inbound channel completes, so identity set by the interceptor is visible to them)
- `src/test/java/io/github/dariogguillen/chess/websocket/StompAuthIT.java` (new; 5 IT cases per the plan; case 3 — `stompConnect_withInvalidJwt_succeedsButAnonymous` — is the CRITICAL pin that proves bad JWT does NOT reject CONNECT; cases 4 and 5 — spoof attempts — assert `handler.poll()` returns the ERROR frame; case 4 additionally subscribes downstream and asserts nothing leaks to the broker; cycle 2 cleanup added imports for `WebSocketHttpHeaders` and `StompCommand` replacing the two FQN sites)
- `docs/architecture.md` (modified; new "WebSocket trust model (feature 20)" subsection under Authentication describes the two-phase interceptor, the "anonymous still works" guarantee, the "ERROR-not-disconnect" choice, the bean-cycle workaround rationale, and the pin-on-first-use fallback; the stale "STOMP identity verification → out of scope" line removed)
- `README.md` (modified; WebSocket subsection mentions optional Authorization-on-CONNECT and identity-spoof prevention; "Out of scope → Authentication" line softened to reflect that the bundle ships and only specific extensions — refresh tokens, password reset, multi-provider OAuth, claim flow — remain deferred; cycle 2 corrected the static test-count claim from 207/209/wrong-values to 212)
- `notes/20-auth-stomp-trust.md` (new; follows `_template.md`; Decisions covers the 5 implementer decisions — bean cycle workaround via GameStore + `@Lazy`, pin-on-first-use, destination-based lookup, interceptor-alone-on-inbound, bad-JWT-stays-anonymous; Gotchas covers native headers vs STOMP headers, `StompHeaderAccessor.setUser`, ERROR-frame mechanics, the bean-cycle discovery and resolution; cycle 2 corrected the count-delta line from 209 to 212)
- `feature_list.json` (modified: `auth-stomp-trust.status` → `done`)
- `progress/current.md` (replaced with a session-closed note; the bundle is complete)
- `progress/history.md` (this entry)

**Feature note:** `notes/20-auth-stomp-trust.md`.

---

## Auth bundle complete (features 16–20) — 2026-05-29

**29 features delivered. 0 in_progress. 0 pending. The auth bundle is complete.**

The five features compose end-to-end:
- **16 `auth-core`** built the foundation — `User` entity, Flyway V2, Spring Security base, `JwtVerifier`, `GET /api/me`. The 401 entry-point was the deliberate gap closed by feature 17.
- **17 `auth-jwt`** locked the JWT shape and added the email/password issuance endpoints `/api/auth/register` and `/api/auth/login`. The 12-code `ErrorResponse` enum was set here.
- **18 `auth-google-oauth`** added Google OAuth 2.0 sign-in alongside email/password — same JWT shape, same `User` aggregate, same downstream filter chain. The two paths converge.
- **19 `auth-my-games`** delivered the actual product benefit the user named when opening the bundle ("con una cuenta se pueden revisar las partidas jugadas") — `GET /api/me/games` paginated, plus the write surface that populates `games.{white,black}_user_id` whenever the corresponding side was authenticated.
- **20 `auth-stomp-trust`** closed the WebSocket gap — identity attached to STOMP sessions, spoof attempts produce ERROR frames, anonymous play preserved end-to-end.

The bundle was originally planned upfront at 5 features with 7 locked technical decisions copy-forwarded into each feature plan so reviewers cross-referenced one source. The decisions held: stateless JWT in Authorization Bearer, HS256 with 7-day lifetime, single `JwtIssuer`/`JwtVerifier`, OAuth callback redirects with URL fragment (log-hygiene choice), CORS allowCredentials stays false, identity link via two FK columns on `games` (the feature-16 cycle-2 data-model decision rather than an intermediate `players` table), anonymous STOMP keeps working never gated by JWT.

**Out-of-scope confirmed at bundle close** (deferred to potential future features, none committed):
- Refresh tokens.
- Email verification, password reset, magic links.
- 2FA / TOTP.
- Account linking (same User with both Google and email/password).
- Claim flow for pre-existing anonymous games.
- Multi-provider OAuth (Apple, GitHub, etc.).
- Rate-limiting on `/api/auth/login` and the move endpoint.
- Refresh-token rotation / revocation / Redis JWT blacklist.

**Operator follow-ups** for the deploy (carried over and accumulated across the bundle):
- Set `AUTH_JWT_SECRET` env var on EC2 (≥ 32 bytes; HS256 secret; boot fails fast without it).
- Create the Google OAuth 2.0 Client ID in Google Cloud Console; set `GOOGLE_OAUTH_CLIENT_ID` and `GOOGLE_OAUTH_CLIENT_SECRET` env vars on EC2 (or the OAuth flow's first invocation will fail at runtime).
- Set `AUTH_OAUTH_FRONTEND_REDIRECT_BASE=https://chess-frontend-52i.pages.dev` on EC2.
- Rotate the RDS master password (pre-existing carryover from 2026-05-25 closure; especially pressing before user accounts exist).
- Configure branch protection on `main` in the GitHub UI (pre-existing carryover from feature 13).
- Replace the static test-count claim in `README.md` with a dynamic count (now reads 212; will drift on every future feature).

The repo returns to maintenance mode. Future work in this codebase enters via new `feature_list.json` entries with priority ≥ 21 — same pattern as features 15 and 16–20 (themselves maintenance reopens past the 2026-05-25 portfolio-closure milestone).

---

## 2026-05-29 — hotfix-deploy-cors (feature 25, retroactive)

**Status:** done. **Retroactive entry — bypassed the full harness cycle because production was broken and the bugs were independently obvious.**

**Summary:** Same-day production hotfix after the auth bundle (16–20) deployed and three latent issues surfaced together. The deploy initially succeeded (Swagger loaded after the user manually added `AUTH_JWT_SECRET` to `/opt/chess/.env`), but the frontend's first cross-origin POST to `/api/rooms` was blocked by CORS. A re-run of the GitHub Actions deploy then failed at the smoke test with the container in a crash loop. Three distinct bugs, in the order they surfaced:

**Bug 1 — `docker-compose.prod.yml environment:` block missed the bundle's env vars.** The yml explicitly declared which env vars to pass to the container (`SPRING_DATASOURCE_*`, `SPRING_DATA_REDIS_*`) and never listed `AUTH_JWT_SECRET`, `AUTH_OAUTH_FRONTEND_REDIRECT_BASE`, `GOOGLE_OAUTH_CLIENT_ID`, `GOOGLE_OAUTH_CLIENT_SECRET`. Docker Compose reads `.env` for substitution inside the yml (`${VAR}` placeholders), but it does NOT pass `.env` entries to the container by default. So even with the four vars correctly set in `/opt/chess/.env`, the recreated container never received them — Spring saw `auth.jwt.secret` as blank and `AuthProperties.Jwt`'s compact constructor failed boot fast with `auth.jwt.secret must be set`. The user's first manual fix (`docker compose restart app`) worked because `restart` reuses the existing container's previously-injected environment — the bundle's first boot must have been built before the bundle code shipped. The CI deploy's `docker compose up -d` recreates the container and exposes the gap. Fix: added the four vars to `docker-compose.prod.yml` `environment:` block as `${VAR}` references so substitution flows from `.env` into the container.

**Bug 2 — `CorsConfig.java` didn't expose a `CorsConfigurationSource` bean.** Pre-bundle, `CorsConfig implements WebMvcConfigurer` and overrode `addCorsMappings(CorsRegistry)`. That API operates at the `DispatcherServlet` level (AFTER the security filter chain). Feature 16's `SecurityConfig.cors(Customizer.withDefaults())` requires a `CorsConfigurationSource` bean to handle preflights at the filter-chain level. Without it, OPTIONS preflights handled by Spring Security returned 200 but with no CORS headers — the browser blocked the cross-origin POST. The reviewer-approved `BearerCorsIT` covered the header echo path via `MockMvc`, but `MockMvc`'s CORS handling differs subtly from the real filter-chain behaviour and did not catch this gap. Fix: added a `@Bean public CorsConfigurationSource corsConfigurationSource()` that reads from the same `CorsProperties` as the existing `addCorsMappings` path, preserving the single-source-of-truth principle from feature 10. Both the filter-chain layer and the dispatcher-level layer now agree on policy.

**Bug 3 — self-inflicted `CHESS_CORS_ALLOWED_ORIGIN_PATTERNS: ${...:-}` empty default overrode application.yml.** The first patch attempt for bug 1 also added `CHESS_CORS_ALLOWED_ORIGIN_PATTERNS: ${CHESS_CORS_ALLOWED_ORIGIN_PATTERNS:-}` to the `environment:` block to keep the CORS allow-list overrideable. The `:-` empty default meant docker compose substituted an empty string when the env var was absent (which it was — no `CHESS_CORS_*` in `/opt/chess/.env`), and the container received `CHESS_CORS_ALLOWED_ORIGIN_PATTERNS=""`. Spring saw the env var as SET-BUT-EMPTY, which overrode `application.yml`'s production default (`https://chess-frontend-52i.pages.dev,http://localhost:*`), and `CorsProperties`'s compact constructor rejected the empty list with `chess.cors.allowed-origin-patterns must be set`. Boot failed again. Fix: removed the line entirely. If a future operator wants to override CORS at runtime, they wire `${CHESS_CORS_ALLOWED_ORIGIN_PATTERNS}` (no `:-` default) and set the value in `.env`. The yml comment documents this explicitly so the next operator doesn't reintroduce the trap.

**Why no full harness cycle:** the bugs were independently confirmed via logs (Spring's boot failure message names the bound property and reason), the patches were small and self-contained (5 lines in the yml, ~25 lines in `CorsConfig.java`), and production was visibly broken — the leader's call was to apply the edits directly and verify locally with `./init.sh` before user push, rather than plan + implementer + reviewer round-trips. The retroactive feature entry plus this history entry document the rastro the harness usually provides.

**Operational lesson surfaced (becomes feature 26):** the `docker-compose.prod.yml` on EC2 is independent of the file in the repo. The `deploy.yml` workflow runs `docker compose pull && up -d` against the yml that already lives on the host — any edit to the yml in the repo never reaches production. The user had to SSH twice during this hotfix to edit `/opt/chess/docker-compose.prod.yml` manually (once for bug 1's fix, once for bug 3's fix), even though both fixes were also committed to the repo. The proper resolution is feature 26 (`deploy-config-sync`), which adds an `scp` step to `deploy.yml` that copies the repo's yml to EC2 on every deploy. This entry stays as a marker that the drift was the underlying enabler of the multi-cycle hotfix.

**Verification at close:** `OPTIONS /api/rooms` from origin `https://chess-frontend-52i.pages.dev` returns 200 with `access-control-allow-origin: https://chess-frontend-52i.pages.dev`, `access-control-allow-methods: GET,POST,PUT,DELETE,OPTIONS`, `access-control-allow-headers: Content-Type` (and the rest of the allow-list on demand), `via: 1.1 Caddy`. Frontend confirmed end-to-end: cross-origin POST creates the room, second player joins, game starts. The full auth bundle is live in production at last.

**Files touched:**

- `docker-compose.prod.yml` (modified; four `AUTH_*` / `GOOGLE_*` env vars added to the app service `environment:` block via `${VAR}` substitution; `CHESS_CORS_ALLOWED_ORIGIN_PATTERNS` line was added then removed in two cycles, with a final comment block explaining why the env-var override is not pre-wired and how to add it safely if ever needed)
- `src/main/java/io/github/dariogguillen/chess/config/CorsConfig.java` (modified; new `@Bean public CorsConfigurationSource corsConfigurationSource()` reading from the same `CorsProperties` as `addCorsMappings`; the original `WebMvcConfigurer.addCorsMappings` path is preserved untouched so the dispatcher-level CORS handling continues to apply to non-Spring-Security request paths)
- `feature_list.json` (new entries `hotfix-deploy-cors` priority 25 status done and `deploy-config-sync` priority 26 status pending)
- `progress/history.md` (this entry)

**No new tests added.** `./init.sh` verified green locally before user push (Spotless auto-format ran on `CorsConfig.java`; no other code changes). Adding an IT that would catch this regression would require a `MockMvc`-without-shortcut test path that exercises the actual filter chain ordering — feasible but out of scope for the hotfix; tracked as a future tightening of `BearerCorsIT` / `CorsConfigIT` if the same shape ever recurs.

**Feature note:** none (the retroactive nature of this entry and the history paragraph here cover the documentation the harness usually places in `notes/NN-*.md`).

---

## 2026-05-29 — color-selection (feature 21)

**Status:** done. Full harness cycle (leader plan → implementer → reviewer → user OK).

**Summary:** Wired the room creator's side preference (WHITE / BLACK / RANDOM) through the create flow. Until now the side was *positional and implicit* — `Room.players[0]` was always the creator and always WHITE, the joiner always BLACK — an invariant locked in by feature 9.5 and read directly by `RoomDetailsMapper` (role-by-index), by `RoomController`'s hardcoded role constants, and by `RoomService.joinRoom` (`white = creator`). Letting the creator pick BLACK breaks that invariant, so the fix **persists the creator's chosen side** on the `Room` and derives every role / white-black decision from it.

**Design:** A new concrete `Side creatorSide` field on the `Room` record. RANDOM is resolved server-side **at create time** (anti-cheat coin flip via a new injectable `RandomSideChooser`, `SecureRandom`-backed, mirroring `RoomCodeGenerator`'s ownership of randomness), so the domain never stores RANDOM — the creator learns their colour immediately in the create response and the joiner always gets the opposite. Request intent is a new `domain/SidePreference` enum `{WHITE, BLACK, RANDOM}`, kept distinct from the domain `Side {WHITE, BLACK}` (a real side-to-move / piece owner).

**Backwards-compatibility** reused the exact record-evolution pattern from `Player.userId` (feature 19): canonical 4-arg `Room` constructor + a convenience 3-arg constructor defaulting `creatorSide = WHITE` + a compact constructor that maps a `null` `creatorSide` to WHITE *before* the other validations. Result: every existing `new Room(...)` call site (RedisRoomStoreIT, RedisTtlIT, RoomTest, RoomDetailsMapperTest) compiled unchanged, and Jackson deserialisation of rooms serialised into Redis before the deploy stays valid (null component → WHITE). `CreateRoomRequest.preferredSide` is nullable and defaults to WHITE, so frontends that never send the field behave exactly as before — additive, backwards-compatible, no coordinated breaking change (the frontend was deliberately kept un-blocked).

**Reviewer verdict:** approved. `./init.sh` green; +14 new test methods (RoomServiceTest 2, RoomTest 3, RoomDetailsMapperTest 1, RoomControllerIT 6, RoomDetailsControllerIT 2), all green. No fully-qualified names, constructor injection only, no new pom deps, README correctly untouched (out of scope), `docs/architecture.md` API-contract updated.

**Files touched:**

- `src/main/java/io/github/dariogguillen/chess/domain/SidePreference.java` (new; request-intent enum)
- `src/main/java/io/github/dariogguillen/chess/service/RandomSideChooser.java` (new; `SecureRandom`-backed `Side choose()` seam)
- `src/main/java/io/github/dariogguillen/chess/domain/Room.java` (modified; `creatorSide` field, canonical + convenience constructors, null→WHITE compact constructor, JavaDoc)
- `src/main/java/io/github/dariogguillen/chess/service/RoomService.java` (modified; injected `RandomSideChooser`; `createRoom` accepts `SidePreference` and resolves it via `resolveCreatorSide`; `joinRoom` assigns the joiner the opposite side and builds the `Game` white/black from the stored side)
- `src/main/java/io/github/dariogguillen/chess/web/room/CreateRoomRequest.java` (modified; nullable `SidePreference preferredSide` with `@Schema`)
- `src/main/java/io/github/dariogguillen/chess/web/room/RoomController.java` (modified; resolved role on create, opposite on join; removed hardcoded role constants)
- `src/main/java/io/github/dariogguillen/chess/web/room/RoomDetailsMapper.java` (modified; roles derived from `creatorSide`; JavaDoc)
- `src/main/java/io/github/dariogguillen/chess/web/room/RoomDetailsResponse.java`, `RoomResponse.java` (modified; `@Schema` / JavaDoc corrected for the chosen-side semantics)
- `src/test/java/io/github/dariogguillen/chess/service/RoomServiceTest.java` (new; stubbed-chooser RANDOM determinism + BLACK-creator white/black assignment)
- `src/test/java/io/github/dariogguillen/chess/domain/RoomTest.java`, `web/room/RoomDetailsMapperTest.java`, `web/room/RoomControllerIT.java`, `web/room/RoomDetailsControllerIT.java` (modified; extended coverage)
- `docs/architecture.md` (modified; API-contract section documents `preferredSide` and the positional→`creatorSide` role-derivation change)
- `notes/21-color-selection.md` (new; feature note)
- `feature_list.json` (`color-selection` status → done)

**Feature note:** [`notes/21-color-selection.md`](../notes/21-color-selection.md).

---

## 2026-05-29 — time-control (feature 22)

**Status:** done. Full harness cycle (leader plan → implementer → reviewer → user OK). One implementer↔reviewer iteration: the reviewer rejected on a single OpenAPI contract-drift issue, the implementer fixed it, the reviewer approved on the second pass.

**Summary:** Added an optional server-authoritative per-player clock with auto-flagging. A room may declare a `TimeControl { initialMs, incrementMs }`; the resulting game tracks remaining time per side, decrements the mover's clock on every move (Fischer increment supported, `incrementMs = 0` = sudden-death), and auto-terminates with the new `GameStatus.TIMEOUT` when the side-to-move runs out — **even if that player is offline**. Clock state is broadcast on every move (`MoveEvent`) and on termination (new `GameTimedOutEvent`, STOMP type `GAME_TIMED_OUT`). The clock is opt-in: a room with no `TimeControl` produces an untimed game whose behaviour is byte-for-byte the pre-feature one (frontend-lag safety, same discipline as feature 21's `preferredSide` default).

**Design decisions (locked in the planning discussion with the user):**
- **Flag detection = per-game scheduled timer, not polling.** `ClockTimerManager` (scheduling over the shared `TaskScheduler`/`Clock`) + `GameTimeoutService` (idempotent terminal flip + archive + broadcast) are the spiritual twins of feature 11's `GracePeriodManager` + `GameAbandonService`. Precise to the ms, no Redis keyspace scan, and the grace (`ABANDONED`) and clock (`TIMEOUT`) terminal paths coexist cleanly through the same `isTerminal()` + `gameStore.compute` idempotency guard — the first to fire wins, the second is a no-op.
- **The clock runs during disconnect, only for the side-to-move.** When the opponent moves while a player is offline, that player's turn begins and `applyMove` reschedules the flag timer for them regardless of connection state. The server cannot reliably distinguish an intentional from an accidental disconnect (same STOMP signal), so the 60s grace period is the only mitigation; pausing the clock would be the exact cheat a server-authoritative clock exists to prevent.
- **Increment supported** with `incrementMs = 0` default.

**Record evolution (backwards-compatible):** `Game` gained nullable clock fields where `null` is a *meaningful* domain state (untimed) — so the compact constructor permits null and enforces an **all-or-nothing invariant** rather than defaulting (contrast feature 21's `creatorSide`, where null defaults to WHITE). A convenience constructor keeps existing `new Game(...)` call sites and Jackson deserialisation of pre-deploy Redis games valid. `Room` gained a nullable `TimeControl`, threaded Request → Room → Game exactly as feature 21 threaded `creatorSide`.

**Two implementer deviations from the literal plan, both reviewer-validated as sound:** (1) a fourth `Game.incrementMs` field (the plan named three) — required because `GameService` has no `RoomStore` to recover the increment from `Room.timeControl`; written once at `joinRoom` from the same immutable source, so no drift is possible. (2) `TimeControlIT` uses `initialMs = 2000ms` (not ~300ms) so the STOMP subscriber registers before white's flag fires at join time — same subscribe-before-timer idiom as `DisconnectHandlingIT`.

**Reviewer's caught regression (fixed before approval):** the new `TIMEOUT` status is archived and surfaced through `GET /api/me/games` and `GET /api/players/{id}/games`, whose `@Schema(allowableValues=...)` did not list it — OpenAPI contract drift, the same discipline feature 6.6 established. Fixed in `MyGameSummary` + `PlayerGameSummary` (+ JavaDoc), plus the `MoveEvent` JavaDoc "four → five variants" correction.

**Files touched:**

New: `domain/TimeControl.java`, `service/ClockTimerManager.java`, `service/GameTimeoutService.java`, `websocket/GameTimedOutEvent.java`, `notes/22-time-control.md`, and tests `domain/TimeControlTest.java`, `domain/GameStatusTest.java`, `service/GameServiceClockTest.java`, `websocket/TimeControlIT.java`.

Modified: `domain/Game.java`, `domain/GameStatus.java` (+`TIMEOUT`, `isTerminal()`), `domain/Room.java`, `service/GameService.java` (`applyMove` clock decrement + reschedule), `service/RoomService.java` (clock init + first timer), `web/game/GameController.java`, `web/game/GameStateResponse.java`, `web/game/PlayerGameSummary.java`, `web/me/MyGameSummary.java`, `web/room/CreateRoomRequest.java`, `web/room/RoomController.java`, `websocket/GameStateEvent.java` (permits), `websocket/MoveEvent.java` (clock fields), tests `domain/GameTest.java`, `domain/RoomTest.java`, `service/RoomServiceTest.java`, `web/game/GameControllerIT.java`, `websocket/GameWebSocketIT.java`, `docs/architecture.md`, `feature_list.json` (status → done).

**Verification:** `./init.sh` green; 0 failures/errors/skipped. README correctly untouched (out of scope — no run-procedure change). Cross-repo change is additive/backwards-compatible.

**Feature note:** [`notes/22-time-control.md`](../notes/22-time-control.md).

---

## 2026-05-29 — spectators-in-room (feature 22.5)

**Status:** done. Full harness cycle (leader plan → implementer → reviewer → user OK), approved on the first review pass.

**Summary:** Re-keyed spectators from the game to the room so the viewer count works from the waiting lobby. Before this, feature 6.5's `ViewerCountTracker` counted STOMP sessions on `/topic/games/{gameId}` and published to `/topic/games/{gameId}/viewers` — but the `gameId` does not exist until the second player joins (`RoomService.joinRoom` creates the `Game`), so a creator who wanted friends watching while waiting for an opponent had nothing for them to subscribe to. The tracker now counts sessions on `/topic/rooms/{roomId}`, excludes players via `room.players()` (matching the trusted `playerId` native header), and publishes `ViewerCountEvent` to `/topic/rooms/{roomId}/viewers`. The count therefore exists from `WAITING_FOR_PLAYER` and stays stable across the `WAITING_FOR_PLAYER → ACTIVE` transition. Game state (`MoveEvent` / the `GameStateEvent` family) stays on `/topic/games/{gameId}`; spectators transition there on `RoomJoinedEvent` (feature 9.5) for the live board.

**Design decisions (taken with the user):**
- **Unified room-keyed count, not a dual game+room count.** The game-keyed `/topic/games/{gameId}/viewers` topic from feature 6.5 was **retired**. The frontend has no viewer-count UI yet, so this was a free STOMP contract change with no production consumer.
- **Minimum spectator surface, no new REST endpoint.** The spectator reuses `GET /api/rooms/{id}` (feature 9.5) for the initial snapshot and the room topic for live updates. The "enriched snapshot" option (adding `creatorSide`/`timeControl` to the room read) was deliberately left out.
- **Access control split out.** Protecting the player slot (a separate join token so a shared watch link cannot be used to join as a player) was carved into a new feature `room-access-tokens` (priority 22.7, pending). This feature does NOT touch the join contract.

**Player exclusion** keeps feature 6.5's trust-on-declaration model, re-pointed at the room: a session sending a `playerId` header matching a member of `room.players()` is excluded. The creator (already subscribed to `/topic/rooms/{roomId}` for `RoomJoinedEvent`) must send its `playerId` on that SUBSCRIBE to self-exclude — a frontend coordination note documented in the architecture doc and feature note.

**Reviewer verdict:** approved (first pass). `./init.sh` green; 126 unit + 128 IT, 0 failures/errors/skipped; `ViewerCountIT` 7 tests. The reviewer explicitly validated three out-of-list JavaDoc-only touch-ups (`RoomEvent`, `GameStateEvent`, `PlayerSessionTracker`) that re-pointed stale `/topic/games/{gameId}/viewers` examples to the new topic — legitimate hygiene driven by the topic retirement, not scope creep.

**Files touched:**

New: `notes/22.5-spectators-in-room.md`.

Modified: `websocket/ViewerCountTracker.java` (core refactor — room-topic regex, `String roomId` maps, exclusion via `RoomService.findById` + `room.players()`, broadcast to `/topic/rooms/{roomId}/viewers`, drops `GameService`), `websocket/ViewerCountEvent.java` (`(String roomId, int count)`), `test/.../websocket/ViewerCountIT.java` (migrated to room keying + the three new cases: counted in WAITING_FOR_PLAYER, stable across join, creator self-excluded; + `setupRoom` helper), `websocket/RoomEvent.java`/`GameStateEvent.java`/`PlayerSessionTracker.java` (JavaDoc-only stale-example fixes), `docs/architecture.md` (room-keyed spectator model), `feature_list.json` (added the `22.5` entry done + the new `22.7 room-access-tokens` pending entry).

**Verification:** `./init.sh` green. README correctly untouched (out of scope). Cross-repo: STOMP contract change (viewers topic game → room; `ViewerCountEvent` shape) is safe — no current frontend consumer; the frontend implements the room-keyed model directly.

**Follow-up queued:** `room-access-tokens` (feature 22.7) — the join-token / two-links (play vs watch) access control surfaced during this feature's planning.

**Feature note:** [`notes/22.5-spectators-in-room.md`](../notes/22.5-spectators-in-room.md).
