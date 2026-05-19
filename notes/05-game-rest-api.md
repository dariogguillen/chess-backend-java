# Feature 05 — Game REST endpoints (move + read)

**Feature ID:** `game-rest-api` (from `feature_list.json`)

**Status:** in progress

---

## What we built

Two HTTP endpoints — `GET /api/games/{id}` and `POST /api/games/{id}/moves` — that
together drive the in-game life cycle from the point feature 4 left it (a freshly
created `Game` in `InMemoryGameStore`) up to the terminal status. The move endpoint
validates the caller against `X-Player-Id`, delegates legality to `ChessRules`, and
persists the updated state via `GameStore.compute(gameId, …)` so two concurrent
move requests on the same game serialize cleanly. Four new domain exceptions
(`GameNotFoundException`, `GameAlreadyEndedException`, `NotYourTurnException`,
`IllegalMoveException`) plug into the existing `GlobalExceptionHandler` without
adding any new branches to the mechanical `codeOf` derivation; two new framework
handlers — `UnprocessableException` (the new 422 family) and Spring's
`MissingRequestHeaderException` — round out the matrix. The `Game` record gains
`startingFen` so the `ChessRules` replay path has the invariant it needs, and
`GameStatus` gains a one-line `isTerminal()` helper. This is also the **first
feature shipped under the springdoc convention from day one** — every new
`@RestController`, `@Operation`, `@ApiResponse`, and DTO `@Schema` annotation lands
in the initial implementer pass, not as a retroactive cleanup.

## Java / Spring concepts that appear

- **`@RequestHeader` for caller identification.** The move endpoint identifies the
  caller via the `X-Player-Id` header bound at the controller boundary as
  `@RequestHeader("X-Player-Id") String playerId`. Spring's `HandlerMethodArgumentResolver`
  reads the header from the request and substitutes it for the parameter; a missing
  header raises `MissingRequestHeaderException` before the method body runs, which
  our `GlobalExceptionHandler` now maps to a 400 / `MISSING_HEADER` with a message
  derived from `ex.getHeaderName()`. See
  [Spring MVC `@RequestHeader`](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-methods/requestheader.html).

- **Jakarta `@Pattern` on record components.** `MoveRequest` declares
  `@Pattern(regexp = "^[a-h][1-8]$")` on `from`/`to` and
  `@Pattern(regexp = "^(KNIGHT|BISHOP|ROOK|QUEEN)$")` on `promotion`. The validator
  runs before the controller body, so the handler never sees garbage like `"i9"`
  or `"E2"`. Two layers of defense: the regex at the wire boundary, the domain
  `Square` / `Piece` compact constructors at the type boundary. The `@Pattern`
  on `promotion` does **not** fail on `null` — Jakarta validation treats absent
  optional fields as "skip the pattern" by design, which is exactly what we want.
  See [Jakarta Bean Validation 3.0 — constraints](https://jakarta.ee/specifications/bean-validation/3.0/).

- **`ConcurrentHashMap.compute` for cross-state invariants (now on the second
  service).** `GameService.applyMove` runs its read-check-write block inside
  `gameStore.compute(gameId, …)`, exactly the same idiom as `RoomService.joinRoom`
  (see notes/04-room-rest-api.md). The lambda runs under a per-bin lock, so two
  concurrent move requests on the same gameId serialize: one wins and applies the
  move, the other observes the post-move state and either succeeds with the next
  side's move or fails with `NotYourTurnException`. The `Game[1] holder` array
  workaround for capturing the produced value out of the lambda re-appears here
  too, with an inline comment cross-referencing the prior site.

- **Mechanical `codeOf` paying off at four new exceptions.** The advice's
  `codeOf(ChessException)` derivation strips the trailing `Exception` and converts
  the simple class name to UPPER_SNAKE_CASE. `GameNotFoundException` →
  `GAME_NOT_FOUND`, `GameAlreadyEndedException` → `GAME_ALREADY_ENDED`,
  `NotYourTurnException` → `NOT_YOUR_TURN`, `IllegalMoveException` →
  `ILLEGAL_MOVE`. Zero new branches in `GlobalExceptionHandler`; the only handler
  code added is the new `UnprocessableException` family (one method, six lines)
  and the `MissingRequestHeaderException` 400 handler.

- **Springdoc annotations from the initial pass.** `GameController` ships with
  `@Tag(name = "Games", …)`, both endpoints have `@Operation(summary = …)`, and the
  full `@ApiResponse` matrix — 200 + 404 on `GET`, 200 + 400 + 404 + 409 + 422 on
  `POST` — references `ErrorResponse` via `@Schema(implementation = …)` on every
  4xx. The `OpenApiIT` canary
  (`apiDocs_includesOperationSummaries`) discovers the new paths automatically;
  no IT change needed beyond the new `GameControllerIT`.

## Decisions taken

**`X-Player-Id` header over a body field for caller identification.**

- Decision: the caller's id arrives as the `X-Player-Id` HTTP header on
  `POST /api/games/{id}/moves`. The `GET` is unauthenticated by design — anyone
  with the gameId can read state (this is what feature 6.5 spectators will rely
  on).
- Alternatives: put `playerId` in the request body alongside `from`/`to`;
  introduce a session cookie (requires session management we do not have);
  introduce an `Authorization` header now (locks the auth strategy prematurely).
- Why: keeps identity out of the payload, where it is easy to confuse with
  domain content. A header is also the natural place for the eventual
  `Authorization: Bearer …` swap when a real auth scheme arrives — the request
  body stays the same, only the handler's identity extraction changes. The
  trade-off is one new framework exception (`MissingRequestHeaderException`)
  the advice has to map; a small, mechanical addition.

**`startingFen` added to the `Game` record.**

- Decision: the `Game` record now carries both `startingFen` (the FEN the game
  began from; fixed at construction) and `fen` (the current position; changes
  per move). At game creation time the two hold the same value and diverge
  after the first move.
- Alternatives: hard-code "standard initial position" inside `ChessRules` and
  bake the assumption into every caller; reconstruct the starting FEN from
  the current FEN minus the move history (impossible — FEN does not carry
  history); store only the history and recompute the starting position from a
  global constant (couples non-standard openings to a future refactor).
- Why: makes the invariant explicit at the domain layer, where it belongs.
  `ChessRules.applyMove` already needs `startingFen` to rebuild the chesslib
  `Board` and replay the history (the threefold-repetition hash trick from
  feature 3); the new field surfaces that dependency on the `Game` aggregate.
  Future features that allow non-standard starting positions (puzzle mode,
  analysis mode) get the seam for free.

**`turn` derived from move count, computed at the response boundary.**

- Decision: `turn` is not stored on `Game`; it is computed at the controller
  mapper as `moves.size() % 2 == 0 ? WHITE : BLACK`.
- Alternatives: store `turn` on `Game` (would denormalise — `turn` is a
  function of `moves.size()`); compute it inside `ChessRules.applyMove`
  (mixes a chess-engine concept with a service one); compute it in the
  service layer (not wrong, but the only consumer is the response shape).
- Why: a derived field belongs at the boundary that needs it, not in the
  aggregate. Clients get a ready-to-display value without parsing the FEN,
  the domain stays normalised, and the rule lives in one place. The same
  computation drives `GameService.applyMove`'s turn check — two sites with
  the same one-line expression is fine.

**`NotYourTurnException` mapped to 422, not 409.**

- Decision: the move endpoint returns 422 (Unprocessable Entity) when the
  caller's id does not match the side to move, alongside the 422 for
  `IllegalMoveException`.
- Alternatives: 409 (Conflict), with the reasoning that the resource is in a
  state that does not accept the request from this caller; 403 (Forbidden),
  with the reasoning that the caller lacks the right to mutate this game.
- Why: 422 expresses "the request is well-formed and the caller is recognized,
  but a domain rule rejects it" — which is exactly what NOT_YOUR_TURN is. The
  request body is valid JSON, the path id exists, the header is present and
  identifies a real player on the game; the only thing wrong is the turn
  order, a chess rule. 409 would be defensible (the same matrix applies for
  `GameAlreadyEndedException`, which **is** a 409 because the game state — not
  the caller — forbids any move) but the symmetry with `IllegalMoveException`
  (which is unambiguously 422) reads better in the matrix. 403 would imply an
  authorization layer we do not have.

**New `UnprocessableException` abstract type in the hierarchy.**

- Decision: extend the exception hierarchy with a new abstract intermediate
  `UnprocessableException` under `ChessException`, mirroring the existing
  `NotFoundException` (404) and `ConflictException` (409). `IllegalMoveException`
  and `NotYourTurnException` extend it.
- Alternatives: declare `IllegalMoveException` and `NotYourTurnException` as
  direct subclasses of `ChessException` and add a per-class `@ExceptionHandler`
  for each in the advice; reuse `ConflictException` (drops the 422 entirely).
- Why: the family-on-supertype shape is what the advice already uses for
  `NotFoundException` and `ConflictException` — one `@ExceptionHandler` per
  family, then `codeOf` distinguishes the concrete cases. The third tier was
  documented in `docs/conventions.md`'s hierarchy diagram from feature 4; this
  feature is just where it becomes real code.

**`MissingRequestHeaderException` handler in `GlobalExceptionHandler`.**

- Decision: a dedicated `@ExceptionHandler(MissingRequestHeaderException.class)`
  returning 400 with `error = "MISSING_HEADER"` and a message naming the
  missing header via `ex.getHeaderName()`.
- Alternatives: let Spring's default `BasicErrorController` produce the 400
  with its own JSON shape (`{ "timestamp": …, "status": 400, "error": "Bad
  Request", … }`), which is different from our `ErrorResponse` envelope.
- Why: one error envelope across the entire API surface. A client should not
  have to special-case "this 400 came from Spring's defaults, not from our
  advice." The handler is mechanical — the header name comes from the
  exception, the rest of the body is the canonical `ErrorResponse`.

## How this compares to what I know

- **`ConcurrentHashMap.compute` vs Cats Effect `Ref.modify`.** Re-stating the
  parallel from notes/04: `Ref[F, A].modify(a => (newA, b))` is a pure,
  monadic call that returns `(newState, B)` in one shot — the auxiliary value
  flows through the type. Java's `compute` lambda has return type `V` (the new
  value) and any auxiliary result has to escape through a side channel — for
  `applyMove`, we use the same `Game[1] holder` trick as `RoomService.joinRoom`.
  Where `Ref.modify`'s type signature carries the second result, `compute`
  punts to a one-element array. Functionally equivalent; ergonomically worse;
  the trade-off Java makes everywhere it touches lambdas.

- **Jakarta `@Pattern` vs `cats.data.Validated` with regex smart
  constructors.** In a Typelevel stack you would write a smart constructor
  for `Square` that returns `Validated[NonEmptyList[Error], Square]` and let
  the http4s entity decoder accumulate the errors. The Spring version splits
  the work: the validator at the controller boundary (`@Pattern` regex) and
  the smart constructor on the type (`Square`'s compact constructor). The
  separation is pragmatic — the validator knows about HTTP-level concerns
  (parameter binding, message conversion) and the smart constructor knows
  about the domain — and the cost is two regex definitions for the same
  alphabet. In a single-language stack you would consolidate; across the
  HTTP boundary the duplication is the price of belt-and-braces validation.

- **`X-Player-Id` header vs http4s `AuthMiddleware`.** The Typelevel idiom is
  `AuthMiddleware[F, T]` wrapping the routes — every request that reaches the
  handler has already been authenticated and carries the `T` (here, the
  player identity) at the type level. Spring's `@RequestHeader` is the
  zero-ceremony equivalent: the parameter is bound by the framework, the
  handler has it as a method parameter, the framework throws if it is
  missing. The difference is type safety: in http4s the routes that need a
  player identity carry it in their signature; in Spring the relationship is
  "the framework will inject it, the handler trusts it, the advice maps the
  missing-header case to 400." Same end state, different guarantees.

## Gotchas / things I learned the hard way

- **The `Game[1] holder` idiom re-appears in the second service.** Documented
  in notes/04 already, but worth flagging that the pattern is now used in
  two services — `RoomService.joinRoom` and `GameService.applyMove`. If a
  third surfaces, it is worth extracting a small helper (a
  `ComputeResult<V, R>` wrapper, or an `AtomicReference<R>` for the result).
  At two sites the duplication is below the threshold.

- **chesslib reports `CHECKMATE` regardless of which side delivered the
  mate.** The status enum is "is the side to move mated", so after
  `1. f3 e5 2. g4 Qh4#` — Black delivering mate to White — chesslib's
  `Board.isMated()` returns true and our `mapStatus` produces `CHECKMATE`.
  The `turn` field on the response is `WHITE` (it would be White's turn to
  move, except White cannot). The Fool's Mate IT pins this behaviour with a
  comment so a future reader is not confused by `turn == WHITE && status ==
  CHECKMATE`.

- **`@Pattern` does not fail on null by design.** Jakarta Bean Validation
  treats `@Pattern(regexp = …)` on a null value as "skip, this is fine" —
  the constraint applies only to non-null values. For `promotion` that is
  the desired behaviour (the field is optional). For `from`/`to` we pair
  `@Pattern` with `@NotBlank` so null is rejected separately; without the
  `@NotBlank`, a null `from` would slip through validation and only be caught
  later by the `Square` compact constructor as an NPE — wrong layer to fail
  at.

- **`@PathVariable` does not need a `@RequestHeader` cousin for OpenAPI to
  document the header.** springdoc's reflection picks up `@RequestHeader`
  automatically and emits it as a `parameters[].in = "header"` entry in the
  spec. No `@Parameter(in = ParameterIn.HEADER)` annotation needed; the
  presence of `@RequestHeader` is enough. The same applies to `@PathVariable`
  → `in = "path"`. springdoc Just Works on the standard Spring annotations,
  which keeps the controller readable.

- **`@Schema(implementation = X.class)` resolves at runtime, not
  compile-time.** A typo in the class reference would not be caught by the
  compiler — the field is `Class<?>`, so any class is valid. The
  `OpenApiIT.apiDocs_includesOperationSummaries` canary plus the new
  `GameControllerIT` are what catch a mistyped `@Schema(implementation = X.class)`
  by failing the spec assertion. Worth knowing if a future refactor renames
  `ErrorResponse`.

- **`MoveDto` became `MoveSummary` nested in `GameStateResponse`.** *(added
  2026-05-19)* The suffix `Dto` is not in the official list
  (`Request`/`Response`/`Event`/`Message`), and a record whose only purpose
  is to be a sub-shape of another record — `MoveDto` had no reference site
  outside `List<MoveDto> moves` — is idiomatic in Java 17+ as a nested
  `public record` on the parent. Outside `GameStateResponse` the type is
  `GameStateResponse.MoveSummary`; inside the parent record's body the
  simple name `MoveSummary` resolves directly. The wire format did not
  change (the JSON keys remain `from`/`to`/`promotion`); only the Java
  location and name did. The OpenAPI components.schemas entry renames from
  `MoveDto` to `MoveSummary` accordingly, which is the only externally
  visible side effect of the refactor.

- **`InMemoryRoomStore` and `InMemoryGameStore` moved from `service/` to
  `cache/`.** *(added 2026-05-19)* The package convention in
  `docs/conventions.md` → "Package layout" already names `cache/` as the
  home for Spring Data Redis repositories and caches. The interfaces
  (`RoomStore`, `GameStore`) stay in `service/` — they are the port the
  service layer consumes — and the in-memory implementations become the
  first adapters in `cache/`. Feature 7's Redis-backed implementations will
  land alongside them without a second restructuring. The annotation
  changed from `@Service` to `@Component` to match the new location's
  semantics: these are adapters, not service-layer use cases, and
  `@Component` reads accurately to anyone walking the `cache/` package.
  Spring's DI resolves the implementation by interface type regardless of
  package, so no consumer or test needed a code change beyond the two
  moved files themselves.

## To dig deeper

- [Spring MVC `@RequestHeader`](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-methods/requestheader.html)
  — including the `required = false` default and how `defaultValue` interacts
  with the rejection path.
- [`MissingRequestHeaderException`](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/bind/MissingRequestHeaderException.html)
  — Spring's framework exception for the missing-header case, with
  `getHeaderName()` / `getParameter()` accessors.
- [Jakarta Bean Validation 3.0 — `@Pattern`](https://jakarta.ee/specifications/bean-validation/3.0/jakarta-bean-validation-spec-3.0.html#builtinconstraints-pattern)
  — the null-skip rule spelled out in the spec.
- [HTTP 422 Unprocessable Entity (RFC 9110 §15.5.21)](https://www.rfc-editor.org/rfc/rfc9110#name-422-unprocessable-content)
  — the canonical reading of 422 vs 409 vs 400, useful for justifying the
  matrix to a reviewer.

## File map

New files:

- `src/main/java/io/github/dariogguillen/chess/exception/UnprocessableException.java` —
  abstract intermediate exception type mapped to HTTP 422.
- `src/main/java/io/github/dariogguillen/chess/exception/IllegalMoveException.java` —
  concrete 422 case for chesslib-rejected moves.
- `src/main/java/io/github/dariogguillen/chess/exception/NotYourTurnException.java` —
  concrete 422 case for moves submitted by the wrong side.
- `src/main/java/io/github/dariogguillen/chess/exception/GameNotFoundException.java` —
  concrete 404 case for unknown game id.
- `src/main/java/io/github/dariogguillen/chess/exception/GameAlreadyEndedException.java` —
  concrete 409 case for moves submitted after a terminal status.
- `src/main/java/io/github/dariogguillen/chess/service/GameService.java` —
  orchestration for `findById` and `applyMove` over `GameStore` + `ChessRules`.
- `src/main/java/io/github/dariogguillen/chess/web/game/GameController.java` —
  the `@RestController` for `/api/games`; full springdoc annotation matrix from
  day one.
- `src/main/java/io/github/dariogguillen/chess/web/game/MoveRequest.java` —
  validated request record for `POST /api/games/{id}/moves`.
- `src/main/java/io/github/dariogguillen/chess/web/game/GameStateResponse.java` —
  unified response record for both endpoints; declares the nested
  `MoveSummary` record (the wire-format per-move shape used inside
  `moves`). Originally introduced as a sibling `MoveDto.java`; the closing
  iteration of 2026-05-19 folded it into `GameStateResponse` as a nested
  record per the rule documented in the Gotchas above.
- `src/test/java/io/github/dariogguillen/chess/web/game/GameControllerIT.java` —
  7-test IT covering get-unknown, get-existing, Fool's Mate sequence,
  wrong-player, illegal-move, move-after-checkmate, and missing-header.
- `notes/05-game-rest-api.md` — this file.

Modified files:

- `src/main/java/io/github/dariogguillen/chess/domain/Game.java` —
  added `startingFen` field (between `black` and `fen`) with null + blank
  invariant; expanded JavaDoc to explain the difference vs `fen`.
- `src/main/java/io/github/dariogguillen/chess/domain/GameStatus.java` —
  added `isTerminal()` helper.
- `src/main/java/io/github/dariogguillen/chess/service/RoomService.java` —
  `joinRoom` now passes `chessRules.standardInitialState().currentFen()` to
  both `startingFen` and `fen` when constructing the new `Game`.
- `src/main/java/io/github/dariogguillen/chess/service/GameStore.java` —
  added `compute(String, BiFunction)` to the store seam for the move path.
- `src/main/java/io/github/dariogguillen/chess/service/InMemoryGameStore.java` —
  implements the new `compute` by delegating to the underlying
  `ConcurrentHashMap`.
- `src/main/java/io/github/dariogguillen/chess/exception/GlobalExceptionHandler.java` —
  two new handlers: `UnprocessableException` → 422 and
  `MissingRequestHeaderException` → 400 (`MISSING_HEADER`).
- `src/test/java/io/github/dariogguillen/chess/domain/GameTest.java` —
  threaded `startingFen` through every existing test plus two new tests for
  the new null + blank invariant.
- `README.md` — added a "Games" subsection with one curl example per endpoint.
