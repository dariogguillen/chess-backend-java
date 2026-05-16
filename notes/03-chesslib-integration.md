# Feature 03 — Server-side move validation

**Feature ID:** `chesslib-integration` (from `feature_list.json`)

**Status:** in progress

---

## What we built

A `ChessRules` service that wraps the [`bhlangonijr/chesslib`](https://github.com/bhlangonijr/chesslib)
library and exposes a single function: given a FEN and a domain `Move`, return
a `MoveOutcome` carrying `(legal, fen, status)`. The contract is uniform — `fen`
always describes the *current* board state (post-move when legal; the input
unchanged when illegal) — so callers never have to remember to fall back to an
earlier FEN variable. `ChessRules` is the only file in the codebase allowed to
import `com.github.bhlangonijr.chesslib`; every other layer works with the
domain types (`Move`, `Square`, `Piece`, `GameStatus`) and an opaque `String`
FEN.

## Java / Spring concepts that appear

- **`@Service` and component scanning.** `ChessRules` carries `@Service`, which
  is a stereotype on top of `@Component`. At startup, Spring's
  `@ComponentScan` (declared implicitly by `@SpringBootApplication` on
  `ChessApplication`) walks the classpath, finds the class, and registers a
  single bean of that type with the `ApplicationContext`. Future callers
  inject `ChessRules` via constructor parameters and Spring resolves the bean
  by type. The annotation itself adds nothing semantic beyond `@Component`; it
  is a label for clarity and for AOP filters that target the "service" layer.
  See the [Spring stereotypes reference](https://docs.spring.io/spring-framework/reference/core/beans/classpath-scanning.html#beans-stereotype-annotations).
- **Constructor injection with zero dependencies.** `ChessRules` has no
  collaborators yet but is written with the canonical pattern in mind — when a
  `Clock`, a `Metrics` client, or a future opening-book lookup arrives, the
  constructor sprouts a parameter and Spring wires it in. We do not use
  `@Autowired` on fields anywhere in the codebase (per `docs/conventions.md`),
  and a default no-arg constructor is the correct starting form because there
  is nothing to declare yet. See the
  [Spring DI reference](https://docs.spring.io/spring-framework/reference/core/beans/dependencies/factory-collaborators.html#beans-constructor-injection).
- **Anti-corruption layer (boundary mapping).** The library has its own
  `chesslib.Move`, `chesslib.Square`, `chesslib.Piece`, and `chesslib.Side`
  types, all of which exist only inside `ChessRules`. The conversion happens
  in three private helpers — `toChesslibMove`, `toChesslibSquare`,
  `toChesslibPromotion` — and the return value uses the domain `GameStatus`.
  This is the [DDD anti-corruption layer](https://learn.microsoft.com/en-us/azure/architecture/patterns/anti-corruption-layer)
  pattern in Java form: a thin layer whose only job is to translate between a
  third-party model and our own.
- **`Optional.map(...).orElse(...)` over a domain `Optional<Piece>`.** Domain
  `Move.promotion()` is `Optional<Piece>`. The mapping picks the side-aware
  chesslib piece when present, and falls back to `chesslib.Piece.NONE` when
  absent — this is chesslib's sentinel for "no promotion". The pattern reads
  exactly like `option.map(...).getOrElse(...)` in Scala (minus the difference
  noted in "Gotchas" below).
- **Switch expression over a sealed-ish enum.** `toChesslibPromotion` uses a
  switch expression with arrow labels (`case KNIGHT -> ...`) — the modern Java
  form introduced in Java 14 and stable since. It is an *expression* that
  yields a value, the compiler enforces exhaustiveness over the six `Piece`
  enum constants, and there is no fall-through to worry about. See
  [JEP 361 — switch expressions](https://openjdk.org/jeps/361).
- **SLF4J parameterized logging.** Invalid FEN inputs log at `WARN` via
  `log.warn("Invalid FEN supplied: {}", fen)`. The `{}` placeholder defers
  string formatting until the log framework decides the level is enabled, so
  there is no concatenation cost when `WARN` is filtered out, and the FEN is
  attached to the structured event rather than baked into a single string.
- **JitPack as a Maven repository.** `chesslib` is *not* on Maven Central. The
  canonical distribution is JitPack, which builds tag-versioned JARs on
  demand from any GitHub repo. We add `https://jitpack.io` to `<repositories>`
  in `pom.xml` and Maven resolves `com.github.bhlangonijr:chesslib:1.3.6`
  against it. See the [JitPack docs](https://docs.jitpack.io/).
- **JUnit 5 `@ParameterizedTest` + `@CsvSource`.** The
  `underpromotion_returnsFenWithExpectedPiece` test runs once per row in the
  CSV: `(KNIGHT, N)`, `(BISHOP, B)`, `(ROOK, R)`. JUnit binds the first column
  to the `Piece` parameter via the built-in enum converter and the second to
  the expected FEN symbol. Three test cases, one method body. See the
  [JUnit 5 parameterized tests guide](https://junit.org/junit5/docs/current/user-guide/#writing-tests-parameterized-tests).

## Decisions taken

**Why `chesslib` instead of writing the rules from scratch.**

- Decision: depend on `com.github.bhlangonijr:chesslib:1.3.6`.
- Alternatives: implement legal-move generation, castling rights, en-passant
  targets, draws by repetition / 50-move / insufficient material from scratch;
  pick a different library (`io.github.wolfraam:chessgame`, the Stockfish
  JNI bindings, etc.).
- Why: chess rules are *dense* (legal move generation alone is a non-trivial
  data structure exercise — magic bitboards, attack tables, pin detection).
  A correct, tested library exists. The portfolio value here is in showing
  **integration discipline** — translating at the boundary, treating the
  library as a black box, keeping its types out of the rest of the code — not
  in reimplementing well-known algorithms. Among Java chess libraries,
  `chesslib` is the most mature and has the cleanest API surface for our
  needs (`Board.loadFromFen`, `Board.legalMoves`, `Board.doMove`,
  `Board.getFen`, `Board.isMated/isStaleMate/isDraw/isKingAttacked`).

**Returning `MoveOutcome` rather than throwing on illegal moves.**

- Decision: `applyMove` returns a value, never throws on chess-level errors.
- Alternatives: throw an `IllegalMoveException` that the global handler maps
  to HTTP 422; return `Optional<NewState>` and lose the rejected status
  information; throw on invalid FEN but return on illegal-but-parseable move.
- Why: throwing would couple the service to HTTP error mapping. The exception
  hierarchy lives in `exception/` and lands with features 4-5 alongside the
  global `@RestControllerAdvice`; defining a half-form of it now and
  refactoring later is more cost than building the value-shape and using it
  uniformly. `MoveOutcome` is also usable from contexts the HTTP layer cannot
  reach: a `MoveService` orchestrator (feature 5), batch analysis, tests,
  and a future engine-evaluation feature.

**Uniform `MoveOutcome` contract — `fen` always reflects the current board.**

- Decision: when `legal == false`, `fen` echoes the input and `status` is the
  status of the unchanged position. When `legal == true`, `fen` is the new
  position and `status` reflects it.
- Alternatives: `(legal, Optional<String> newFen, Optional<GameStatus>
  newStatus)` where the optionals are empty on illegal; a sum type
  `LegalMove(newFen, status) | IllegalMove(reason)`.
- Why: the caller never has to remember "if illegal, fall back to my old fen
  variable" — the returned record always carries the right value. The
  redundancy on the illegal path costs three lines per call site
  (`return new MoveOutcome(false, fen, mapStatus(board));`) and removes one
  bug-shaped footgun. A sum type would express intent more sharply but Java's
  pattern-matching switch over sealed interfaces is heavier than a boolean
  for a two-case enum, and the redundant `fen` on the illegal branch is the
  same shape callers will want for the unhappy-path API anyway.

**Invalid FEN is conflated with illegal-move for now.**

- Decision: a `Board.loadFromFen(...)` throw is caught, logged at `WARN`, and
  returned as `MoveOutcome(false, fen, ONGOING)` — the same shape as a normal
  rejection.
- Alternatives: throw a `MalformedFenException` to distinguish from
  `IllegalMoveException`; return a three-state `MoveOutcome` with an explicit
  `parseError` flag.
- Why: an unparseable FEN is a programmer error in this codebase — the only
  producer of FEN is `Board.getFen()` after a successful move, plus the
  starting position constant. A typed exception is more honest, but adding a
  third state to `MoveOutcome` that no caller uses now is friction for no
  payoff. The `WARN` log surfaces it for ops; when the exception hierarchy
  lands in feature 4-5, we split the two cases.

**Legal-move check via `legalMoves().contains(move)` instead of relying on
`doMove(move, true)`.**

- Decision: enumerate the legal-move set with `board.legalMoves()` and check
  membership before calling `doMove`. Two passes over the same data.
- Alternatives: trust `Board.doMove(move, true)` (chesslib's "full
  validation" overload) to reject illegal moves.
- Why: this was a hard-earned discovery. `doMove(move, true)` in chesslib
  1.3.6 performs *structural* checks (a piece exists at the source, the right
  color owns it, the promotion field is well-formed) but does **not** check
  whether the move is in the position's legal-move set. A pawn jumping three
  squares from its starting rank, for instance, is happily applied by
  `doMove(_, true)`. The bug surfaced immediately in
  `illegalMove_returnsOriginalFenAndPrevStatus`, which asserts that `e2-e5`
  from the start is rejected. The fix is to use the authoritative
  `legalMoves()` enumeration, which is the same set `chesslib` itself walks
  to detect mate / stalemate, so we know it is the source of truth.

**`chesslib` pulled from JitPack, not Maven Central.**

- Decision: declare `https://jitpack.io` in `<repositories>` and the
  coordinate `com.github.bhlangonijr:chesslib:1.3.6`.
- Alternatives: vendor the JAR locally; switch to a Maven-Central-resident
  library.
- Why: `bhlangonijr/chesslib` is the most mature Java chess library and is
  not published to Maven Central. JitPack is the canonical alternative — it
  builds tag-versioned JARs from GitHub on demand and caches them. The cost
  is one extra `<repository>` entry and a slightly slower first build.
  Vendoring a JAR works once and then rots; switching libraries to gain
  Maven-Central convenience would mean reassessing rule correctness against
  a less proven engine. `1.3.6` is the latest stable tag (plan suggested
  `1.3.4`; `1.3.6` is two patch versions newer with no breaking changes).

**Service-level `GameState` record — option A over B and C.** *(added 2026-05-15)*

- Decision: a `record GameState(String startingFen, List<Move> history,
  String currentFen, GameStatus currentStatus)` lives in `service/`.
  Construction is via `ChessRules#initialState(String)`; subsequent
  states come from `MoveOutcome#state()`. The service stays stateless;
  every call rebuilds a chesslib `Board`, loads `startingFen`, replays
  `history`, and only then evaluates the candidate move.
- Alternatives:
  - **B — opaque class holding a long-lived chesslib `Board`,
    `Board.clone()`d per move.** chesslib's `Board` is `Cloneable` and
    the clone is roughly O(1). This would skip the replay cost on the
    hot path. Rejected because the persistence path (Redis in feature
    7, Postgres in feature 8) cannot serialize a live `Board`; we
    would still need to round-trip through `(startingFen, history)`
    and replay on reload, duplicating the model. The replay cost we
    are "saving" is sub-millisecond — invisible on any HTTP path.
  - **C — no wrapper at all; `ChessRules#applyMove(String startingFen,
    List<Move> history, Move move)`.** Same replay cost as A, but the
    signature carries three loosely-related parameters and there is
    no explicit "state" abstraction to name. Future callers (engine
    analysis, batch replay, the WebSocket layer, persistence) would
    each invent their own bundle. Rejected for cohesion.
- Why A: stateless service is the easier thing to reason about and
  test, the record composes trivially with the rest of the codebase
  (every other domain type is a record), and `(startingFen, history,
  currentFen, currentStatus)` is exactly the shape Redis and Postgres
  will serialize when the persistence features land. The deciding
  factor was serialization symmetry — option B optimises the hot path
  while forcing a second model for persistence; option A is one model
  end to end.

**Two construction paths, no public canonical constructor in spirit.**

- Decision: `GameState` is only meant to be constructed via the
  factory methods on `ChessRules`. The canonical record constructor
  stays public — Java does not let us hide it cleanly — but the
  JavaDoc points callers at `initialState(fen)` and the implicit
  flow through `MoveOutcome#state()`. The compact constructor does
  not re-derive `currentFen`/`currentStatus` from `startingFen +
  history`, because that would defeat the caching the cached fields
  exist for.
- Alternatives: a private constructor with a static factory
  (impossible with records); validate the cached views by
  re-running chesslib in the compact constructor (defeats caching).
- Why: documented contract beats forced invariants here. The risk is
  a caller constructing a `GameState` with inconsistent cached
  fields; the cost of preventing it via re-derivation is paying for
  the cache twice on every construction.

**Invalid FEN: throw on `initialState`, not return.** *(added 2026-05-15)*

- Decision: `initialState(fen)` throws `IllegalArgumentException` (with
  the offending FEN in the message) when chesslib cannot parse the
  string. `applyMove` does not encounter unparseable FENs because the
  FEN is held inside `GameState` and was already validated at
  construction.
- Alternatives: keep the previous shape — return `MoveOutcome(false,
  ...)` and log at `WARN`. Define a `MalformedFenException` in the
  exception hierarchy.
- Why: an unparseable FEN is a programmer error in this codebase. The
  only producer of FEN is `Board.getFen()` after a successful move,
  plus a starting-position constant. Conflating "I cannot interpret
  your input" with "this move is illegal in this position" was a
  workaround in the absence of an exception hierarchy and in the
  absence of a separate construction step. Now that there *is* a
  separate construction step (`initialState`), the distinction is
  cheap: validation lives at the boundary, runtime moves are the
  domain event. The dedicated typed exception belongs to features
  4-5 alongside the rest of the hierarchy; for now the standard
  `IllegalArgumentException` is the honest shape.

## How this compares to what I know

This section is for a reader coming from Scala / Typelevel (Cats, Cats
Effect, Doobie, http4s, circe).

- **`@Service` auto-discovery vs constructing the service in `IOApp`.** In a
  Typelevel program you instantiate services at the edge of the world,
  inside `IOApp.run`:

  ```scala
  object Main extends IOApp.Simple {
    val run = for {
      rules <- IO.pure(new ChessRules)
      svc   <- IO.pure(new MoveService(rules))
      _     <- serve(svc)
    } yield ()
  }
  ```

  In Spring the same wiring happens reflectively. `@SpringBootApplication`
  on `ChessApplication` turns on component scanning across the
  `io.github.dariogguillen.chess` package tree; the container finds every
  `@Component`/`@Service`/`@Repository`/`@Controller` and constructs them
  once. When another bean's constructor declares a `ChessRules` parameter,
  Spring resolves it by type. The end result is the same — a singleton — but
  there is no `main` you can read top-to-bottom and see the graph. The graph
  is implicit in the annotations.

- **Constructor injection with zero deps vs `Resource[F, A]`.** In Typelevel,
  a service is typically threaded through a `Resource[IO, A]` so that
  acquisition and release are tracked. Even a pure service that needs no
  cleanup is often wrapped in `Resource.eval(IO.pure(new Svc))` for
  composition. In Spring, the canonical form is just a constructor — even an
  empty one — because the bean's lifecycle is managed by the container.
  Adding a dependency later means adding a parameter to the constructor and
  letting Spring figure it out; you do not have to rework the wiring graph
  at the call site.

- **Anti-corruption layer in Java vs in Scala.** This pattern is the same
  shape both ecosystems. In a Doobie app you would write:

  ```scala
  def toRowMove(m: domain.Move): db.MoveRow = ...
  def fromRowMove(r: db.MoveRow): domain.Move = ...
  ```

  and keep them in a `boundary` package that the rest of the code does not
  import. The Java version of this feature does exactly that, just with
  `chesslib` instead of a DB row schema:

  ```java
  private static chesslib.Move toChesslibMove(domain.Move m, Side s) { ... }
  ```

  Same intent: keep one canonical model and translate at the edge. The Java
  twist is that without strong language-level package privacy enforcement
  (Scala has `private[chess]`; Java's `package` access is the closest
  equivalent but works at compile time only), the "only `ChessRules` imports
  chesslib" rule is a convention enforced in code review, not by the
  compiler. A reviewer-time grep is the lightweight check; an ArchUnit test
  would be the heavier one if we wanted to make it structural.

- **Returning `MoveOutcome` vs `Either[IllegalMove, NewState]`.** In
  Typelevel the natural shape would be:

  ```scala
  def applyMove(fen: Fen, move: Move): Either[ChessError, NewState]
  ```

  and callers `.flatMap` through it. The Java code returns a record with a
  boolean discriminator instead. Same intent — *errors are values, the
  caller handles them* — different ergonomics:

  - There is no monadic plumbing in Java for `Either` (no
    for-comprehensions, no `flatMap` chains across types). A `boolean
    legal` + always-present `fen`/`status` reads cleanly when the caller
    is a controller deciding between `200` and `422`.
  - We trade off the compile-time guarantee that the unhappy and happy
    branches use different shapes. `MoveOutcome.fen()` is always present
    even if the move was illegal. That cost is paid back at the call site:
    "the FEN is always the current board" is a simpler invariant than
    "look at `legal`, then maybe look at `newFen`."
  - The `Either` form would also force an upstream decision about the
    typed error: do we have one `IllegalMove`, or a sum of
    `MoveNotInLegalSet | MalformedFen | NotYourTurn`? That decision lands
    with the exception hierarchy in features 4-5. Returning a record now
    means we do not pre-commit to the shape of that sum.

- **JUnit 5 parameterized tests vs ScalaTest `Table` / MUnit
  parameterized.** ScalaTest's `forAll` on a `Table` and MUnit's
  `parameterized` macro do the same job: one method body, multiple inputs.
  The JUnit equivalent uses an annotation and an external data source:

  ```java
  @ParameterizedTest(name = "underpromotion to {0} writes \"{1}\" on a8")
  @CsvSource({
    "KNIGHT, N",
    "BISHOP, B",
    "ROOK,   R"
  })
  void underpromotion_returnsFenWithExpectedPiece(Piece target, String fenSymbol) { ... }
  ```

  versus ScalaTest:

  ```scala
  val cases = Table(
    ("piece", "fenSymbol"),
    (Piece.Knight, "N"),
    (Piece.Bishop, "B"),
    (Piece.Rook,   "R"),
  )
  forAll(cases) { (piece, fenSymbol) =>
    underpromotion(piece) shouldStartWith fenSymbol + "7/"
  }
  ```

  Both compile-check the parameter list against the method signature; both
  generate a test per row in the surefire/test report. JUnit's enum
  conversion ("KNIGHT" → `Piece.KNIGHT`) is automatic; ScalaTest's
  parameter is whatever value you put in the tuple.

- **`@CsvSource` row strings vs Scala literal tuples.** The Scala form
  carries types end-to-end; the JUnit form parses strings via the built-in
  argument converters. For enums and primitives this is invisible. For
  anything more structured (e.g. a `Move`), you would write a custom
  `ArgumentsProvider` or use `@MethodSource` that returns
  `Stream<Arguments>`. Worth knowing but not needed here.

## Gotchas / things I learned the hard way

- **`Board.doMove(move, true)` is not actually full validation.** I
  initially wrote `if (!board.doMove(chessMove, true)) ...` trusting the
  parameter name. The `illegalMove_returnsOriginalFenAndPrevStatus` test
  failed because chesslib accepted `e2-e5`. The "full validation" overload
  only checks structural conditions, not move legality. The fix was
  `if (!board.legalMoves().contains(chessMove)) ...` before calling
  `doMove`. The test became the regression check.
- **`chesslib.Piece.NONE` is the sentinel for "no promotion".** The `Move`
  constructor that takes a promotion expects a `Piece`, never a `null`. The
  enum has a `NONE` value for exactly this purpose. Passing `null` would
  NPE inside chesslib's move-list comparison code; the safe shape is
  `move.promotion().map(...).orElse(Piece.NONE)`.
- **Domain `Square` is lowercase, chesslib `Square` is uppercase.** The
  domain `Square` record holds a lowercase string ("e4"); chesslib's
  `Square` is an enum whose names are uppercase ("E4"). The translation is
  one `toUpperCase()` call inside `toChesslibSquare`. Worth being explicit
  about because reversing it (passing lowercase to `Square.valueOf`) throws
  `IllegalArgumentException` and the failure mode is loud but the cause is
  not obviously about case.
- **`chesslib` is not on Maven Central.** I spent five minutes assuming it
  was. The Maven Central API returns zero results for
  `g:com.github.bhlangonijr a:chesslib`. The plan said "pick the latest
  stable available on Maven Central" — the literal answer is "there is
  none". The right interpretation was JitPack, which is where every guide
  and the project's own README direct you.
- **The `loadFromFen` exception surface is loose.** chesslib does not
  declare what `loadFromFen` throws; in practice depending on the malformed
  input it can be `IllegalArgumentException`,
  `StringIndexOutOfBoundsException`, `NumberFormatException`, or
  `MoveGeneratorException`. Catching `RuntimeException` is the honest
  shape; catching only `MoveGeneratorException` leaks other parse failures
  as bare stack traces. The repo's "structured logging + WARN" idiom
  swallows the type detail because the contract on this side is "we cannot
  interpret this FEN."
- **chesslib's `Board.isDraw()` reads from internal history, not from
  the FEN — a FEN-only API silently misses threefold repetition.**
  *(added 2026-05-15)* The first version of `ChessRules` took
  `(fen, move)` and did `new Board()` + `loadFromFen(fen)` per call.
  This passed every test we could write. It also silently misreported
  threefold repetition as `ONGOING`, because the `Board` instance has
  no history when constructed from a single FEN — and a FEN does not
  encode history (only the 50-move halfmove counter). The user caught
  this in validation. **The lesson is API-shape, not chess.** If a
  scenario reachable in the real domain cannot be expressed as a test
  against your API, your API does not support that scenario. The fix
  was to introduce `GameState(startingFen, history, ...)` and replay
  `history` on every call, which preserves chesslib's internal hash
  and makes `isDraw()` actually correct. The new
  `threefoldRepetition_returnsDrawStatus` test is the regression
  canary. In Scala/Typelevel the same discipline shows up in
  property-based testing: if your `forAll` generator cannot produce
  the input that exhibits the bug, the type signature of the
  function-under-test was lying to you.
- **Hiding chesslib's same-named types from public signatures.**
  *(added 2026-05-15)* chesslib has its own `Move`, `Square`, and
  `Piece` classes that collide by simple name with our domain types.
  The first version of `ChessRules` wrote
  `public MoveOutcome applyMove(String fen,
  io.github.dariogguillen.chess.domain.Move move)` so that the
  reader could tell which `Move` the parameter meant. The convention
  added in `docs/conventions.md` flips that: import the domain
  type, leave the third-party type fully-qualified, but only
  *inside* the anti-corruption layer. The reader of the public API
  sees `Move`, `Square`, `Piece` — the project's vocabulary; the
  chesslib types are an implementation detail visible only in
  helper bodies. The verbosity moved one layer in, where it
  belongs. The grep check `grep -n
  "io.github.dariogguillen.chess.domain"
  src/main/java/.../ChessRules.java` now only matches the import
  block, not any signature.

## To dig deeper

- [bhlangonijr/chesslib README](https://github.com/bhlangonijr/chesslib) —
  short, the API surface fits on one screen.
- [JitPack documentation](https://docs.jitpack.io/) — how the build-on-demand
  resolution works.
- [Forsyth-Edwards Notation (FEN) on Wikipedia](https://en.wikipedia.org/wiki/Forsyth%E2%80%93Edwards_Notation)
  — fields, ordering, en-passant target encoding (which the test for en
  passant relies on).
- [Spring stereotype annotations](https://docs.spring.io/spring-framework/reference/core/beans/classpath-scanning.html#beans-stereotype-annotations)
  — what `@Service` is and is not.
- [JUnit 5 parameterized tests](https://junit.org/junit5/docs/current/user-guide/#writing-tests-parameterized-tests)
  — `@CsvSource`, `@EnumSource`, `@MethodSource`, custom converters.

## File map

- `pom.xml` — added JitPack `<repository>` and the
  `com.github.bhlangonijr:chesslib:1.3.6` dependency.
- `src/main/java/io/github/dariogguillen/chess/service/MoveOutcome.java` —
  record `(boolean legal, String fen, GameStatus status)` with a uniform
  contract: `fen` always reflects the current board state.
- `src/main/java/io/github/dariogguillen/chess/service/ChessRules.java` —
  the `@Service` that wraps chesslib. `applyMove(fen, move)` is the entire
  public API. Imports `com.github.bhlangonijr.chesslib` here and nowhere
  else.
- `src/test/java/io/github/dariogguillen/chess/service/ChessRulesTest.java`
  — unit tests for every acceptance scenario: legal opening, illegal move
  (pawn jump three), check (not mate), Fool's Mate, stalemate, kingside and
  queenside castling, en passant, promotion to queen, parameterized
  underpromotion to knight/bishop/rook, invalid FEN.
