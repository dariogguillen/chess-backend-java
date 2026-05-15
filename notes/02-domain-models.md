# Feature 02 — Core domain models

**Feature ID:** `domain-models` (from `feature_list.json`)

**Status:** done

---

## What we built

The first pure layer of the application: a set of records and enums under
`io.github.dariogguillen.chess.domain` describing the core types — `Side`,
`Piece`, `RoomStatus`, `GameStatus`, `Square`, `Player`, `Move`, `Room`,
`Game`. Each record's compact constructor enforces the structural
invariants that make the type a "smart" value: a `Square` is always
syntactically valid, a `Room` never holds three players, a `Game` never
has the same player on both sides, a `Move` never has equal `from`/`to`
or a nonsensical promotion target. None of these types know about
Spring, persistence, or chesslib; they are the lingua franca every later
layer maps to and from.

## Java / Spring concepts that appear

- **Records and compact constructors.** A record is Java's transparent
  carrier for an immutable group of values: the compiler generates a
  canonical constructor, the accessors (`from()`, `to()`, ...),
  `equals`, `hashCode`, and `toString`. The "compact constructor" form
  (no parameter list, just a body) runs **before** the canonical
  constructor assigns fields, which makes it the right place for
  validation and for reassigning a defensive copy back to the field name
  (e.g., `players = List.copyOf(players);` inside `Room`). See the
  [JEP 395 spec](https://openjdk.org/jeps/395) and the
  [language reference for records](https://docs.oracle.com/en/java/javase/21/language/records.html).
  All nine domain types use this pattern; the records with collection
  fields (`Room`, `Game`) reassign through `List.copyOf` to make the
  field structurally unmodifiable.
- **`Objects.requireNonNull`.** The idiomatic null guard in modern Java:
  it throws `NullPointerException` with the supplied message ("the name
  of the bad parameter") and returns the argument so it can be inlined.
  We use it in every compact constructor for fields that must not be
  null. Reference:
  [`java.util.Objects#requireNonNull(T, String)`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/Objects.html#requireNonNull(T,java.lang.String)).
- **`List.copyOf` for defensive snapshots.** `List.copyOf(coll)` returns
  an unmodifiable `List` containing the elements in iteration order at
  the time of the call. Because the returned list refuses mutation, the
  record's automatically-generated accessor — which returns the field
  directly — is automatically safe to expose. See
  [`List#copyOf`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/List.html#copyOf(java.util.Collection)).
  Both `Room.players()` and `Game.moves()` rely on this; the `RoomTest`
  and `GameTest` "defensively copy" cases prove it.
- **`Optional<T>` as a semantically optional field.** Effective Java
  item 55 warns against `Optional` as a field type in general (it is
  not designed for it, it is not `Serializable`, and it adds an extra
  allocation per access). For records that act as value carriers at
  layer boundaries, the trade-off flips: making "promotion or not"
  explicit at the type level is more valuable than the extra
  allocation. We use `Optional<Piece>` in `Move.promotion()`. Reference:
  [`Optional`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/Optional.html).
- **`enum` as a closed set of singletons.** Java's `enum` is a
  restricted ADT: a fixed set of named instances, optionally carrying
  methods. `Piece` exposes `isPromotionTarget()` as a method on the
  enum itself — no separate utility class, no `switch` at every call
  site. Reference:
  [JLS §8.9 — Enum Classes](https://docs.oracle.com/javase/specs/jls/se21/html/jls-8.html#jls-8.9).
- **`IllegalArgumentException` for invariant violations.** Java
  convention: throw `IllegalArgumentException` when the caller hands
  the API obviously bad arguments. The repo's domain exception
  hierarchy under `exception/` is reserved for business errors that
  map to HTTP responses (feature 4 onward). Construction-time
  violations are programmer errors, and standard JDK exceptions are
  the right vocabulary. Reference:
  [`IllegalArgumentException`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/IllegalArgumentException.html).
- **AssertJ for test assertions.** `assertThatThrownBy`,
  `assertThatNullPointerException`, fluent `hasMessageContaining` —
  these read like the test name and keep one assertion subject per
  test. AssertJ is on the classpath via
  `spring-boot-starter-test`. Reference:
  [AssertJ core](https://assertj.github.io/doc/).
- **JUnit 5 parameterized tests with `@EnumSource`.** `MoveTest` uses
  `@ParameterizedTest` + `@EnumSource(value = Piece.class, names = { ... })`
  to feed the four legal promotion targets through one test method.
  `SquareTest` uses `@ValueSource(strings = ...)` for the
  same pattern over strings. Reference:
  [JUnit 5 parameterized tests](https://junit.org/junit5/docs/current/user-guide/#writing-tests-parameterized-tests).
- **Spotless + google-java-format on the `validate` phase.** The
  formatting check is wired into Maven's `validate` phase, before
  `compile`. A misformatted file fails the build immediately with a
  per-file diff. Workflow: write code, `./mvnw spotless:apply`, then
  `./init.sh`. Reference:
  [spotless-maven-plugin](https://github.com/diffplug/spotless/tree/main/plugin-maven).

## Decisions taken

**Records over mutable classes, even for types that will be persisted.**

- Decision: every domain type is a `record`. Persistence will live in a
  separate `persistence/` package with `@Entity`-annotated classes and
  explicit mappings.
- Alternatives: mutable classes with getters/setters (JPA's natural
  fit) right now, so the "persistence-ready" form is already there.
- Why: JPA requires no-arg constructors and mutable fields; records
  cannot meet that contract. Mixing JPA and domain in the same type
  means persistence concerns leak into the model (entity state machine,
  detached/managed instances, dirty checking). Keeping the domain pure
  now means we will not have to retrofit it when feature 8 lands; the
  cost is one more mapping per persisted type.

**Single `Piece` enum + `isPromotionTarget()` helper, not a separate
`PromotionPiece` type.**

- Decision: `Piece` carries all six kinds and exposes
  `isPromotionTarget()`. `Move.promotion()` is `Optional<Piece>` and
  rejects `PAWN`/`KING` at construction time.
- Alternatives: a separate `PromotionPiece` enum with the four legal
  targets, used as the type of `Move.promotion()`.
- Why: the separate enum gives compile-time guarantees in exactly one
  place (the `Move` field) and forces a conversion at every other
  place a piece appears — FEN parsing, captures, board representation
  in later features. Compile-time guarantees only pay off if the rest
  of the code participates in them; otherwise the conversions are
  pure friction. We chose the single-enum + runtime-validation
  trade-off and made the validation a one-liner via the enum helper.

**`Square` rejects uppercase rather than normalizing to lowercase.**

- Decision: `new Square("A1")` throws. The constructor requires
  `'a'..'h'` for the file character.
- Alternatives: normalize internally (`value = value.toLowerCase()`)
  so both `new Square("a1")` and `new Square("A1")` succeed and end up
  equal.
- Why: a record's generated `equals` is component-wise on the stored
  field. If we normalized inside the compact constructor, the stored
  field would always be lowercase and equality would behave correctly
  — but that hides input handling inside the type. The cleaner contract
  is "this type holds canonical, lowercase algebraic notation; produce
  one or be rejected". Callers that get input from the network can
  lower-case once at the boundary; this type stays simple. Both options
  work; the rejecting variant has fewer surprises.

**`IllegalArgumentException` for construction-time failures, not a
custom domain exception.**

- Decision: invariants are enforced with `IllegalArgumentException`
  (and `NullPointerException` via `Objects.requireNonNull`). The
  `exception/` hierarchy is unused at this layer.
- Alternatives: a `DomainValidationException` extending `ChessException`,
  caught by the global handler.
- Why: domain construction failures are programmer errors, not
  business errors. The service layer is what receives untrusted client
  input; it is responsible for translating bad input to business
  exceptions and HTTP statuses. The domain types stay strict.

**`Optional<Piece>` field on `Move`, despite Effective Java item 55.**

- Decision: `Move.promotion` is `Optional<Piece>`. Constructor rejects
  `null`; callers pass `Optional.empty()` for non-promotion moves.
- Alternatives: a nullable `Piece` field with explicit "null means no
  promotion" semantics.
- Why: item 55's reasoning targets long-lived domain entities and JPA
  beans where the per-access allocation matters and `Optional`'s
  non-`Serializable` nature hurts. For a small record at a layer
  boundary, the type-level honesty of "this is sometimes present" is
  worth the cost. The constructor's null guard is the explicit signal
  that the absence is encoded in `Optional.empty()`, not in `null`.

**No `RoomId` / `GameId` value types yet.**

- Decision: ids are plain `String`s.
- Alternatives: wrap with `RoomId(String)` / `GameId(String)` records
  to prevent mixing.
- Why: there is exactly one place each id appears (the record that
  owns it). Adding value types now would be cargo-culted from the
  Scala/Typelevel reflex of "wrap every primitive". When feature 4+
  shows real confusion in call sites, we will refactor — records make
  it cheap. YAGNI.

## How this compares to what I know

This section is for a reader coming from Scala / Typelevel
(Cats, Cats Effect, Doobie, http4s, circe).

- **Records vs case classes.** A record looks like a `case class`:
  immutable, value-based equality, autogenerated accessors,
  `toString`. The differences that matter:
  - There is no companion object; you cannot define
    `Move.apply(from, to)` that returns an `Either[Error, Move]`. The
    "smart constructor returns a sum" pattern is not idiomatic in
    Java. Validation lives in the compact constructor and signals
    failure by throwing.
  - There is no `copy(...)` method. Re-deriving a record with one
    changed field means writing
    `new Game(g.id(), g.roomId(), g.white(), g.black(), newFen,
    g.status(), g.moves())` by hand. (Java 25 is rumoured to add
    `with`-expressions; until then, this is the cost.)
  - Records' `equals` is generated via `invokedynamic` bootstrapping
    `ObjectMethods.bootstrap` — same spirit as Scala 3's case class
    macros: the compiler doesn't emit the body, the runtime
    materializes it. The behavior is component-wise equality with
    `Objects.equals` semantics, so deep equality is automatic.
  - Records can be pattern-matched as of Java 21 (`switch (x) { case
    Move(var from, var to, var p) -> ... }`), which is the closest
    Java has to Scala's structural matching.

- **Compact constructor vs `Validated[E, A]` smart constructor.** In
  Scala the canonical "make-or-fail" pattern is

  ```scala
  object Square {
    def apply(s: String): ValidatedNec[String, Square] =
      if (regex matches s) Valid(new Square(s))
      else Invalid(NonEmptyChain.one(s"bad square: $s"))
  }
  ```

  The structural Java equivalent is

  ```java
  public Square {
    Objects.requireNonNull(value, "value");
    if (...) throw new IllegalArgumentException("bad square: " + value);
  }
  ```

  Same intent: invalid instances are unrepresentable past
  construction. Opposite error style: Scala accumulates failures in a
  sum type that the caller has to unpack with `andThen`/`map`; Java
  throws at the call site and the caller has to choose between
  letting the exception propagate, catching it, or pre-checking the
  input. The trade-off is real:

  - Scala forces the caller to handle the failure explicitly. Java
    relies on the contract being followed; an unchecked
    `IllegalArgumentException` is "programming error", not a value to
    be handled.
  - Scala can accumulate multiple errors in one pass (`Validated` is
    an applicative); Java stops at the first throw.

  At the layer we are writing — the pure domain, called by the
  service layer — the Java idiom is fine. The service layer will
  validate untrusted input *before* constructing domain types, and
  catch the unchecked exception at the boundary only as a safety
  net.

- **`Optional<Piece>` vs `Option[Piece]`.** Same shape for the common
  cases: `map`, `flatMap`, `orElse`, `isPresent`/`isEmpty`. The
  Java-specific gotchas:
  - `Optional` was not designed to be a field type (Effective Java
    item 55). It is fine as a return type, and fine as a record
    component when the field is *semantically* optional and the
    record is a value carrier (our case). It is wrong as a JPA
    `@Column` field.
  - `Optional` is not `Serializable`. This matters if you put it in a
    type that gets persisted via Java serialization (which is rare in
    modern stacks, but worth knowing).
  - `Optional` is not nullable. `new Move(from, to, null)` is a bug;
    callers pass `Optional.empty()`.
  - Chaining `flatMap` on `Optional` is more awkward than on `Option`
    in Scala — there is no `for`-comprehension. For one level of
    optionality we never feel it.

- **Java `enum` vs Scala 3 `enum` / sealed traits.** Java's `enum`
  is a closed set of named singletons with optional methods on each
  case. It has no payload. `Side`, `RoomStatus`, `GameStatus`, and
  `Piece` are all payload-free, so `enum` is the right tool. When a
  later feature needs payload (e.g. a `ConnectionState` with cases
  `Connected`, `Disconnected(playerId)`, `Reconnecting(playerId,
  graceDeadline)`), the Java idiom is **sealed interface + records
  per case**:

  ```java
  sealed interface ConnectionState
      permits ConnectionState.Connected,
              ConnectionState.Disconnected,
              ConnectionState.Reconnecting {
    record Connected() implements ConnectionState {}
    record Disconnected(String playerId) implements ConnectionState {}
    record Reconnecting(String playerId, Instant deadline)
        implements ConnectionState {}
  }
  ```

  Pattern-matchable in `switch`, exhaustive at compile time. That is
  the Scala-3-enum-with-payload structural equivalent.

- **`List.copyOf` vs Scala's immutable `List`.** In Scala this idiom
  feels alien: passing a `List` already hands the callee an
  immutable structure, so there is nothing to copy. In Java, `List`
  is an interface; the caller might pass an `ArrayList` and mutate
  it after the record holds it. `List.copyOf` produces an
  unmodifiable snapshot, and the record's automatic accessor returns
  it directly. The `RoomTest.shouldDefensivelyCopy_*` case proves
  exactly this: a mutable input is held captured at the time of
  construction.

- **`Objects.requireNonNull` vs `Option.fold` / Typelevel guards.** In
  Scala you would lift a nullable into `Option` at the boundary
  (`Option(x)`) and never see null past that point. Java's modern
  null guard is `Objects.requireNonNull(arg, "arg")`: it throws
  `NullPointerException` (not `IllegalArgumentException`) with the
  argument name as the message — both clear and free. We use it for
  every reference field at construction time.

## Gotchas / things I learned the hard way

- The compact constructor *can* reassign the parameter
  (`players = List.copyOf(players);`); that reassignment ends up in
  the canonical field. Forgetting it makes the record hold the
  caller's mutable `List`, and the "defensive copy" test fails
  because subsequent mutations leak in.
- `assertThatNullPointerException()` (no parentheses on the method
  reference inside the lambda — it's a static import from AssertJ
  that returns a builder) was the cleanest way to assert a NPE with a
  specific message. AssertJ's `assertThatThrownBy(...)` works too but
  reads less directly when the expected type is `NullPointerException`.
- Spotless re-flowed the long `assertThatThrownBy(() -> ...)` chains
  in `GameTest` into deeply nested column-100-aware wraps. Running
  `./mvnw spotless:apply` once before `./init.sh` saved a build cycle.
- `@EnumSource(names = { ... })` matches by `Enum.name()` (case
  sensitive). Typo'd `"Queen"` instead of `"QUEEN"` and the
  parameterized test silently dropped to zero invocations. JUnit warns
  with a clear message but the surefire summary still shows the test
  as run with `tests=0`.

## To dig deeper

- [JEP 395 — Records](https://openjdk.org/jeps/395)
- [Java records language reference](https://docs.oracle.com/en/java/javase/21/language/records.html)
- [Effective Java 3rd ed., Item 55 — Return optionals judiciously](https://www.oreilly.com/library/view/effective-java-3rd/9780134686097/)
- [JUnit 5 Parameterized Tests](https://junit.org/junit5/docs/current/user-guide/#writing-tests-parameterized-tests)
- [AssertJ — fluent assertions](https://assertj.github.io/doc/)
- [Sealed classes (JEP 409)](https://openjdk.org/jeps/409) — for the
  next time we need an ADT with payload.

## File map

- `src/main/java/io/github/dariogguillen/chess/domain/Side.java` —
  enum: `WHITE`, `BLACK`.
- `src/main/java/io/github/dariogguillen/chess/domain/Piece.java` —
  enum: six piece kinds + `isPromotionTarget()` helper.
- `src/main/java/io/github/dariogguillen/chess/domain/RoomStatus.java` —
  enum: `WAITING_FOR_PLAYER`, `ACTIVE`, `CLOSED`.
- `src/main/java/io/github/dariogguillen/chess/domain/GameStatus.java` —
  enum: `ONGOING`, `CHECK`, `CHECKMATE`, `STALEMATE`, `DRAW`,
  `ABANDONED`.
- `src/main/java/io/github/dariogguillen/chess/domain/Square.java` —
  record wrapping a lowercase algebraic notation string, validated in
  the compact constructor.
- `src/main/java/io/github/dariogguillen/chess/domain/Player.java` —
  record with `id` (non-blank) and `displayName`.
- `src/main/java/io/github/dariogguillen/chess/domain/Move.java` —
  record `(from, to, Optional<Piece> promotion)` with structural
  validation.
- `src/main/java/io/github/dariogguillen/chess/domain/Room.java` —
  record with id, players (defensive copy, max 2, unique ids),
  status.
- `src/main/java/io/github/dariogguillen/chess/domain/Game.java` —
  record with two-sided game state, defensive copy of the move
  history.
- `src/test/java/io/github/dariogguillen/chess/domain/SquareTest.java`
  — happy path, null, blank, length, file/rank range, uppercase
  rejection.
- `src/test/java/io/github/dariogguillen/chess/domain/MoveTest.java`
  — happy path, parameterized promotion targets, equal `from`/`to`
  rejection, PAWN/KING rejection, null guards.
- `src/test/java/io/github/dariogguillen/chess/domain/RoomTest.java`
  — 0/1/2 player constructions, 3-player rejection, duplicate id
  rejection, null guards, unmodifiable view, defensive copy.
- `src/test/java/io/github/dariogguillen/chess/domain/GameTest.java`
  — happy construction, same-player-both-sides rejection, null/blank
  id rejection, null fen/status/moves rejection, unmodifiable view,
  defensive copy.
