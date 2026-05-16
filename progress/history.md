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
