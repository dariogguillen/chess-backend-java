# Feature 04 — Room REST endpoints (in-memory)

**Feature ID:** `room-rest-api` (from `feature_list.json`)

**Status:** in progress

---

## What we built

Two HTTP endpoints — `POST /api/rooms` and `POST /api/rooms/{id}/join` — that
together drive the room lifecycle: the first player creates a room and is given
the white pieces; the second player joins, takes black, and the act of joining
also creates the `Game`. State is held in process via `InMemoryRoomStore` and
`InMemoryGameStore` (both thin wrappers over `ConcurrentHashMap`), behind
`RoomStore` / `GameStore` interfaces that feature 7 will replace with
Redis-backed implementations without touching `RoomService`. The same feature
introduces the first `@RestControllerAdvice` global exception handler in the
project, mapping our `exception/` hierarchy and Spring's framework exceptions
(`MethodArgumentNotValidException`, `HttpMessageNotReadableException`) to a
uniform `{ error, message, timestamp }` body.

## Java / Spring concepts that appear

- **`@RestControllerAdvice` + `@ExceptionHandler`.** A controller-advice bean is
  a global cross-cutting handler: Spring's `ExceptionHandlerExceptionResolver`
  consults it whenever a controller method throws. We map four families —
  `NotFoundException` → 404, `ConflictException` → 409,
  `MethodArgumentNotValidException` → 400 with `VALIDATION_FAILED`,
  `HttpMessageNotReadableException` → 400 with `MALFORMED_REQUEST` — and
  produce an `ErrorResponse` record carrying `(error, message, timestamp)`. The
  `Clock` bean is constructor-injected so the timestamp is deterministic in
  tests. See the
  [Spring docs for exception handling](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-exceptionhandler.html).

- **Jakarta Bean Validation on records (`@Valid` + `@NotBlank`).**
  `CreateRoomRequest` and `JoinRoomRequest` are records whose `displayName`
  component carries `@NotBlank`. `@Valid` on the controller parameter tells
  Spring to invoke the validator before the method body runs; a failure becomes
  a `MethodArgumentNotValidException` that our advice catches. The validation
  starter is already on the classpath transitively via `spring-boot-starter-web`
  — see "Decisions taken" for the verification step. See
  [Jakarta Bean Validation 3.0](https://jakarta.ee/specifications/bean-validation/3.0/).

- **`ConcurrentHashMap.compute`.** The map's `compute(key, remappingFunction)`
  runs the function under a per-bin lock — two threads `compute`-ing on the same
  key serialize. We use it in `RoomService.joinRoom` to make
  "read room → check invariants → write room + write game" atomic against
  another concurrent join on the same room. The game write happens *inside* the
  lambda so a losing race never observes a half-state. See
  [`ConcurrentHashMap#compute`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ConcurrentHashMap.html#compute(K,java.util.function.BiFunction)).

- **Constructor injection of four collaborators.** `RoomService` depends on
  `RoomStore`, `GameStore`, `RoomCodeGenerator`, and `ChessRules`. The
  parameter list grows but the form does not change: final fields, constructor
  assignment, no `@Autowired`. Spring resolves each parameter by type at
  context startup. Testability stays trivial — the IT autowires the real
  beans; a future unit test could construct the service with mocks in two
  lines. See
  [Spring DI reference](https://docs.spring.io/spring-framework/reference/core/beans/dependencies/factory-collaborators.html#beans-constructor-injection).

- **`@RestController` vs `@Controller` + `@ResponseBody`.** `@RestController`
  is a one-shot composite that implies `@ResponseBody` on every method, so the
  return value is serialized straight to the response body via Jackson. The
  alternative — `@Controller` plus `@ResponseBody` on each method — is the
  same wiring spelled out twice; we never have a reason to render a server-side
  view, so `@RestController` is always the right choice for this project.

- **`@ResponseStatus(HttpStatus.CREATED)` on a controller method.** The
  default status for a returned body is 200; declaring `@ResponseStatus`
  changes it without writing a `ResponseEntity` wrapper. The create endpoint
  uses it to return 201 on the happy path while the join endpoint stays on the
  default 200.

- **`SecureRandom` for short codes.** `RoomCodeGenerator` uses
  `java.security.SecureRandom` rather than `java.util.Random`. The codes are
  not security-sensitive in any cryptographic sense — they are short and
  guessable by design — but `SecureRandom` is the safer default: it is
  thread-safe, seeded from system entropy, and removes the "use `ThreadLocal`
  to avoid contention on `Random`" footnote entirely. The marginal cost is
  invisible at one call per room creation.

## Decisions taken

**Room id as a 6-char short code, not a UUID.**

- Decision: a six-character code drawn from the alphabet
  `ABCDEFGHJKMNPQRSTUVWXYZ23456789` (23 letters + 8 digits = 31 chars; omits
  the visually ambiguous `I` / `L` / `O` / `0` / `1`). Collision handling is
  in `RoomService.createRoom`, which retries up to 5 times before throwing.
- Alternatives: a UUID (no collisions, but unfriendly to share aloud); a
  shorter code with a wider alphabet; a longer code with a smaller alphabet;
  a sequential id (predictable, leaks pacing information).
- Why: the code is meant to be read aloud or sent over a chat channel. UUIDs
  are 36 characters and full of dashes; a six-character code is what every
  similar product converges to (Jackbox, Kahoot, Zoom meeting ids). The
  31-char alphabet gives `31^6 ≈ 8.87 × 10^8` codes — more than enough for a
  portfolio app that holds tens of active rooms, far short of the bottleneck
  point. At scale this becomes a per-tenant problem; not relevant here.

**The game is created at join time, not via a separate
`POST /api/rooms/{id}/start`.**

- Decision: the second player's `POST /api/rooms/{id}/join` is the only event
  that creates the `Game`. The response of the join call returns a non-null
  `gameId` ready for feature 5 (move endpoint) to consume.
- Alternatives: model a "ready but not started" intermediate state with a
  separate start call (more endpoints, more state, more error cases — what if
  the first player closes the room before clicking start?); have the creator
  start the game in `POST /api/rooms` with the first player as both white and
  black (impossible by `Game`'s compact constructor); always start a game on
  room creation and treat the second join as a join-of-an-existing-game.
- Why: a real user has nothing to do between "second player joined" and
  "first move played", so the intermediate state has no UX. Collapsing the
  two transitions into one removes a state from the room lifecycle (`READY`)
  and an endpoint (`start`) and a possible race ("second player joined but
  creator hasn't clicked start yet — can the joiner play?"). The
  `RoomResponse.gameId` field is nullable to keep one record across the two
  endpoints; on create it is `null`, on join it is the freshly created id.

**`RoomStore` / `GameStore` as interfaces from day one.**

- Decision: each store is declared as an interface and the in-memory
  implementation is the only bean today. Feature 7 will add a Redis-backed
  bean and the in-memory one will be removed; no caller changes.
- Alternatives: inject `InMemoryRoomStore` directly today and extract the
  interface in feature 7 when the second implementation arrives.
- Why: the second implementation is on the near-term roadmap, the interface
  is small (three methods on `RoomStore`, two on `GameStore`), and naming it
  now anchors the seam — the consumer (`RoomService`) never sees the
  in-memory shape, so the day-7 swap is a one-bean replacement rather than a
  refactor of consumers. The cost is two extra files; the saving is one
  feature's worth of churn.

**Server-generated `playerId`, no authentication.**

- Decision: `playerId` is a freshly minted `UUID.randomUUID().toString()` on
  every create/join request, returned to the caller in the response, and
  trusted on subsequent requests without verification.
- Alternatives: bind the `playerId` to a session cookie or a signed token
  (JWT, HMAC) so the server can verify the caller on every move; require an
  external identity provider.
- Why: the project intentionally defers authentication (see
  `docs/architecture.md` — "Authentication is out of scope"). The signed-token
  binding is the natural follow-up when reconnect logic lands in feature 9 or
  when the project adds a session layer; surface this as a known limitation
  rather than design around it now.

**Atomicity via `ConcurrentHashMap.compute` rather than `synchronized`.**

- Decision: the read-check-write block in `joinRoom` runs as the remapping
  function inside `roomStore.compute(roomId, ...)`. The `Game` is also saved
  inside the lambda so the cross-store invariant ("a game exists iff its room
  is `ACTIVE`") never has a window where it can be observed broken.
- Alternatives: a `synchronized` block keyed on a per-room object held in a
  side map; a `ReentrantLock` per room; transactional Redis later — none of
  which exists today.
- Why: `ConcurrentHashMap.compute` gives exactly the property we need
  (serialization on a specific key) without us inventing a lock-table data
  structure. The lambda style is awkward Java (the `Game[]` hack to surface
  the created game to the outer code is the price), but it is the right
  primitive for this shape. The Redis swap in feature 7 will replace this
  with a watch/multi/exec block or a Lua script; same operation, same
  contract, different engine.

## How this compares to what I know

- **`@RestControllerAdvice` vs `EitherT[F, AppError, A]` + an http4s
  interpreter.** The Typelevel idiom is to keep errors as values: the service
  returns an `EitherT[F, AppError, A]`, and the HTTP layer pattern-matches on
  the `AppError` ADT to choose `Status` and body. Spring's pattern splits the
  same work in two: services *throw* typed exceptions (still values, but
  exceptions are how Java spells "non-local return"), and the advice plays the
  role of the interpreter — `@ExceptionHandler(NotFoundException.class)` is
  the analogue of `case AppError.NotFound(_) => NotFound(...)`. Same intent,
  different ergonomics: we lose the type-level guarantee that the controller
  enumerates every error case (the compiler will not warn if we miss one),
  and gain the ability for any layer in the call stack to throw without the
  signature carrying the error type. The trade-off is the standard "checked
  exceptions vs. values" debate replayed at the framework level.

- **`ConcurrentHashMap.compute` vs Cats Effect `Ref.modify`.** A `Ref[F, A]`
  in Cats Effect gives the same single-cell atomic semantics with very
  different ergonomics: `ref.modify(a => (newA, b))` is a pure, monadic call
  inside the `F` context; the compiler tracks the effect, the lambda is just
  a function. `ConcurrentHashMap.compute` is callback-style — the lambda runs
  synchronously, returns the new value (or `null` to delete), and the caller
  has to smuggle any auxiliary results out (we use a single-element `Game[]`
  array, the canonical Java workaround for "lambda needs to set a local"
  because Java requires captured locals to be effectively final). The
  fundamental capability is the same; the surface is more honest in Cats
  Effect because the effect is in the type.

- **Jakarta `@Valid` vs `cats.data.Validated`.** `Validated[E, A]` (and its
  sibling `ValidatedNel`) is constructor-driven: a smart constructor returns
  a `Validated`, the caller `.andThen`s through it, the compiler tracks the
  error type. Spring's Bean Validation is annotation-driven and runs at the
  controller boundary — the controller method body never sees an invalid
  input, the validator fails first and our advice maps it to a 400. The
  Typelevel form composes better when you need to accumulate errors across
  fields; the Java form is closer to the metal of "HTTP request validation"
  because the validator knows about parameter binding, path variables, and
  message conversion in the same pass.

- **The `Game[]`-of-one trick vs a `Ref` return.** In Scala, `Ref.modify`
  returns `(newState, B)` and you can lift any auxiliary value out of the
  closure in the type signature. In Java, the lambda passed to `compute` is a
  `BiFunction<K, V, V>` — its return type is fixed to `V`, the new value.
  Any auxiliary result has to escape through a side channel. The convention
  is a `final` one-element array (`Game[] holder = new Game[1]`) — odd, but
  recognized at sight by every Java reader, and the alternatives (a custom
  return wrapper, a thread-local, a `CompletableFuture`) are all worse for
  three lines of code.

- **Node parallel for the same flow.** The Node version had no explicit
  store-interface seam; rooms lived in a singleton module's `Map` and the
  routes called it directly. The Java rewrite makes the seam visible and
  swappable, which is the architectural payoff for the extra ceremony. The
  exception advice has no Node equivalent that we used; the Node app spelled
  out `if (!room) return res.status(404).json(...)` in every handler. The
  Java advice is one place to change the shape of every error body, which is
  the long-game payoff.

## Gotchas / things I learned the hard way

- **`Board.STARTING_POSITION_FEN` does not exist on chesslib 1.3.6.** The
  task hand-off said `Board` exposes a `public static final String
  STARTING_POSITION_FEN`. The constant actually lives on
  `com.github.bhlangonijr.chesslib.Constants` as
  `Constants.startStandardFENPosition`. Caught with `javap -p` on the jar;
  the import in `ChessRules` had to add `Constants` and the body of
  `standardInitialState()` uses `Constants.startStandardFENPosition`.

- **`HttpMessageNotReadableException` is the right handler for `{not-json`,
  not a Jackson exception.** The first cut tried `JsonProcessingException`;
  Spring wraps Jackson failures into `HttpMessageNotReadableException` before
  the advice sees them. The latter is part of `spring-web`, not the
  validation starter, and is the canonical class to map.

- **`@Valid` on a record parameter validates the record's annotated
  components without further ceremony.** Records play cleanly with the
  validator: annotations on the components become metadata on the record's
  canonical constructor parameters, which is where the validator looks. No
  `@Valid` cascade needed inside the record because the components are
  primitives / `String`.

- **The advice's `codeOf` derivation depends on the simple class name.** If
  a future exception is named, say, `IllegalMoveException`, `codeOf` will
  produce `ILLEGAL_MOVE`. If we ever rename a class without updating the
  documented error code in this note (and in the README), the API contract
  shifts silently. Worth flagging now; the alternative is a per-class
  `errorCode()` method, which is heavier weight than the derivation deserves
  at three exceptions in scope.

- **The third `joinRoom` branch was removed on 2026-05-18.** *(added
  2026-05-18)* The original `compute` lambda had a third check on
  `existing.status() != RoomStatus.WAITING_FOR_PLAYER` that the HTTP path
  could not reach in this feature: the size check fires first, and no
  production code today produces `RoomStatus.CLOSED`. The test that "covered"
  it only succeeded by seeding a `CLOSED` room directly through the autowired
  store, bypassing the controller. Feature 9 (`disconnect-handling`) is the
  first place expected to produce `CLOSED` from a real path; it will
  reintroduce the appropriate check and a real HTTP-path test together when
  the closing semantics are actually decided.

- **Room ids are case-insensitive at the boundary; other path-variable ids
  are not.** *(added 2026-05-18)* `roomId` is a short, human-shared code, so
  the controller normalizes the `{id}` path variable to uppercase before
  calling the service; every response body returns the canonical uppercase
  form regardless of how the client wrote the id in the URL, so the client
  never sees two representations of the same room. Other ids that will land
  with later features (`gameId` in feature 5, `playerId` references) stay
  case-sensitive because they are server-generated UUIDs, not shared by
  humans. The normalization uses `toUpperCase(Locale.ROOT)` explicitly:
  Java's default-locale `toUpperCase` misbehaves in Turkish locales — `i`
  maps to `İ`, not `I` — and our alphabet is ASCII, so `Locale.ROOT` is the
  safe, locale-independent choice.

## To dig deeper

- [Spring `@RestControllerAdvice`](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-exceptionhandler.html)
  — the parts of the model that are not immediately obvious: order of advice
  beans, narrowing by package, exposing model attributes, etc.
- [`ConcurrentHashMap#compute`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ConcurrentHashMap.html#compute(K,java.util.function.BiFunction))
  — the JDK Javadoc spells out the locking contract precisely.
- [Jakarta Bean Validation 3.0 spec](https://jakarta.ee/specifications/bean-validation/3.0/)
  — the constraint catalog plus the rules for composing constraints.
- [Spring Boot `spring-boot-starter-validation`](https://docs.spring.io/spring-boot/docs/3.5.x/reference/htmlsingle/#io.validation)
  — what the starter pulls in and how it activates.

## File map

New files:

- `src/main/java/io/github/dariogguillen/chess/exception/ChessException.java` —
  abstract `RuntimeException` root of the project's exception hierarchy.
- `src/main/java/io/github/dariogguillen/chess/exception/NotFoundException.java` —
  abstract intermediate type for "resource missing"; mapped to 404.
- `src/main/java/io/github/dariogguillen/chess/exception/ConflictException.java` —
  abstract intermediate type for "state conflict"; mapped to 409.
- `src/main/java/io/github/dariogguillen/chess/exception/RoomNotFoundException.java` —
  concrete 404 case for unknown room id.
- `src/main/java/io/github/dariogguillen/chess/exception/RoomFullException.java` —
  concrete 409 case for "room already has two players".
- `src/main/java/io/github/dariogguillen/chess/exception/ErrorResponse.java` —
  the `{ error, message, timestamp }` record returned by the advice.
- `src/main/java/io/github/dariogguillen/chess/exception/GlobalExceptionHandler.java` —
  `@RestControllerAdvice` mapping our hierarchy plus Spring's framework
  exceptions to the shared body.
- `src/main/java/io/github/dariogguillen/chess/service/RoomStore.java` —
  storage interface for rooms; `findById`, `save`, `compute`.
- `src/main/java/io/github/dariogguillen/chess/service/InMemoryRoomStore.java` —
  `ConcurrentHashMap`-backed implementation.
- `src/main/java/io/github/dariogguillen/chess/service/GameStore.java` —
  storage interface for games; `findById`, `save`.
- `src/main/java/io/github/dariogguillen/chess/service/InMemoryGameStore.java` —
  `ConcurrentHashMap`-backed implementation.
- `src/main/java/io/github/dariogguillen/chess/service/RoomCodeGenerator.java` —
  `@Component` producing 6-char codes from the documented alphabet.
- `src/main/java/io/github/dariogguillen/chess/service/RoomService.java` —
  orchestration for create + join + game-creation.
- `src/main/java/io/github/dariogguillen/chess/web/room/CreateRoomRequest.java` —
  validated request record for `POST /api/rooms`.
- `src/main/java/io/github/dariogguillen/chess/web/room/JoinRoomRequest.java` —
  validated request record for `POST /api/rooms/{id}/join`.
- `src/main/java/io/github/dariogguillen/chess/web/room/RoomResponse.java` —
  unified response record `{ roomId, playerId, role, gameId }`.
- `src/main/java/io/github/dariogguillen/chess/web/room/RoomController.java` —
  the `@RestController` for `/api/rooms`.
- `src/test/java/io/github/dariogguillen/chess/service/RoomCodeGeneratorTest.java`
  — unit tests covering length, alphabet, no-ambiguous-chars, no-duplicates
  over 1000 samples each.
- `src/test/java/io/github/dariogguillen/chess/web/room/RoomControllerIT.java` —
  integration tests for create happy path, join happy path, the two HTTP-reachable
  error paths (404 / 409 ROOM_FULL), plus validation and malformed-body cases.

Modified files:

- `src/main/java/io/github/dariogguillen/chess/service/ChessRules.java` — added
  `standardInitialState()` plus the `Constants` import for chesslib's
  starting-position constant.
- `src/test/java/io/github/dariogguillen/chess/service/ChessRulesTest.java` —
  added one test covering `standardInitialState()`.
- `README.md` — added the two endpoints to the API table with `curl`
  examples.
- `docs/architecture.md` — added a paragraph under "Layered architecture"
  about the `RoomStore` / `GameStore` seam and the feature-7 swap.
