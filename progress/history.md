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
