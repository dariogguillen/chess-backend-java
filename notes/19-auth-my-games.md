# Feature 19 — Authenticated game history (`auth-my-games`)

**Feature ID:** `auth-my-games` (from `feature_list.json`)

**Status:** in progress

---

## What we built

The user-visible payoff of the auth bundle. Two surfaces ship together:

1. **Read surface — `GET /api/me/games?page=&size=`.** A new authenticated
   endpoint returning the caller's archived games in the standard Spring
   Data `Page<MyGameSummary>` envelope, newest first. Auth-required: a
   missing or invalid Bearer JWT returns 401 with the
   `AUTHENTICATION_REQUIRED` `ErrorResponse` envelope. Pagination params:
   `page` (default 0, min 0), `size` (default 20, min 1, max 100); out-
   of-range values surface as 400 `VALIDATION_FAILED`.
2. **Write surface — authenticated game creation populates the FK
   columns.** When `POST /api/rooms` or `POST /api/rooms/{id}/join` is
   called with a valid Bearer JWT, the resulting `Player` carries the
   user's id (in a new nullable `Player.userId` field), the domain
   `Game` carries it through to the archive path, and on terminal-
   status archive `GameEntity` persists `games.white_user_id` or
   `games.black_user_id` (the FK columns feature 16 added in V2 but
   left dormant). For anonymous calls the columns stay null. The
   existing guest-friendly `GET /api/players/{id}/games` endpoint
   stays open and unchanged.

This is where the user goal lands: *"con una cuenta se pueden revisar
las partidas jugadas"*.

## Java / Spring concepts that appear

- **`@AuthenticationPrincipal` resolved against the populated
  `SecurityContext`.** Feature 16's `JwtAuthenticationFilter` populated
  a `UsernamePasswordAuthenticationToken` whose principal is the
  `User` JPA entity. Spring's `AuthenticationPrincipalArgumentResolver`
  resolves the parameter directly from the security context on every
  controller invocation — no `SecurityContextHolder.getContext()...`
  ceremony required. On anonymous requests the principal is `null`,
  which lets the same controller method accept both guest and
  authenticated callers (`RoomController.createRoom`,
  `RoomController.joinRoom`). On `MyGamesController.getMyGames` the
  principal is non-null by the security filter's construction:
  unauthenticated calls are 401'd before the controller body runs.
- **Spring Data `Page<T>` + `Pageable` + JPQL pagination.** A repository
  method returning `Page<T>` with a `Pageable` parameter is Spring
  Data's idiomatic pagination shape. The framework derives the count
  query (`SELECT COUNT(g) FROM GameEntity g WHERE …`) automatically
  from the value query, drops projections that don't affect row count
  (the `SIZE(g.moves)` subquery), and assembles the standard JSON
  envelope (`content`, `totalElements`, `totalPages`, `size`, `number`,
  `first`, `last`, `numberOfElements`, `empty`). `PageRequest.of(page,
  size)` is the builder. See [Spring Data JPA reference — Special
  parameter handling](https://docs.spring.io/spring-data/jpa/reference/repositories/query-methods-details.html#repositories.special-parameters).
- **`@Validated` + `@Min`/`@Max` on `@RequestParam`.** Jakarta
  Validation annotations on individual controller parameters are
  **only** picked up when the controller class is annotated with
  Spring's `@Validated`. Without it, `@Min(0)` on `int page` is
  silently ignored — `@Valid` on a `@RequestBody` activates a different
  machinery (post-processor in `RequestResponseBodyMethodProcessor`).
  Constraint failures surface as `HandlerMethodValidationException`
  in Spring 6.1+ (previously `ConstraintViolationException`). The
  `GlobalExceptionHandler` now maps both to 400 `VALIDATION_FAILED`
  so the wire shape stays uniform with `@RequestBody` validation
  failures. See [Spring Framework reference — Validation,
  Data Binding, and Type
  Conversion](https://docs.spring.io/spring-framework/reference/core/validation/beanvalidation.html#validation-beanvalidation-spring-method).
- **Jackson record deserialization with new nullable fields.** The
  domain `Player` record gained a third nullable component `userId`.
  Existing Redis-active games (created before this deploy) were
  serialised against the two-field shape; deserialising them after
  the deploy is the cross-version compatibility question. Jackson
  uses the record's canonical (3-arg) constructor as the
  `@JsonCreator` and supplies `null` for missing JSON properties on
  reference types — same default as bean deserialization. The compact
  constructor MUST accept null on `userId` for this to work; we
  removed the `Objects.requireNonNull` accordingly. The convenience
  `Player(id, displayName)` constructor we added for code ergonomics
  is invisible to Jackson — it picks creators by field-count match
  against the canonical constructor.
- **`ArchivedGamePlayerView` JPQL constructor projection.** The
  `SELECT new <fqn>(...)` clause in JPQL invokes the record's
  canonical constructor at result-set extraction time. Adding two
  new fields (`whiteUserId`, `blackUserId`) means every existing
  JPQL query that materialises this projection must include the new
  fields in the SELECT list — JPA throws at query-validation time
  if the constructor signature and the SELECT list diverge. Spring
  Data does not catch this mismatch at compile time; the boot-time
  query-validation does.

## Decisions taken

**Decision: refactor `GameStateResponse` and `RoomJoinedEvent` to use
dedicated `PlayerView` wrapper records instead of embedding the
domain `Player` raw.**

- *What:* `GameStateResponse.PlayerView(UUID id, String displayName)`
  replaces the previous `Player white` / `Player black` components.
  Symmetrically, `RoomJoinedEvent.PlayerView` replaces the previous
  `Player blackPlayer`. The controllers / services build the
  `PlayerView` at the wire boundary, dropping `userId` explicitly.
- *Alternatives considered:* mark `Player.userId` with
  `@JsonIgnore`. Rejected because the annotation lives on the
  domain type — it couples the domain to the wire format, and
  Jackson's behaviour for records + `@JsonIgnore` is fragile (some
  combinations require `@JsonIgnoreProperties` instead). The wrapper
  record is the structurally honest answer: the wire format is its
  own type with its own components.
- *Why this one:* future fields on `Player` cannot accidentally leak.
  Anyone reading `GameStateResponse.java` sees the wire shape
  enumerated explicitly; anyone reading `RoomJoinedEvent.java` sees
  the same on the STOMP side. The cost is a one-line `toPlayerView`
  helper in `GameController` and a `PlayerView.of(Player)` factory
  on `RoomJoinedEvent.PlayerView`. The pattern matches
  `RoomDetailsResponse` / `PlayerInRoom`, which had already adopted
  this discipline on the room-details surface in feature 9.5.

**Decision: extend `ArchivedGamePlayerView` with `whiteUserId` /
`blackUserId`; do self-side determination in the controller.**

- *What:* `ArchivedGamePlayerView` gained two new `UUID` fields. The
  controller compares the authenticated user's id against
  `view.whiteUserId()` and picks `selfSide` + `opponentDisplayName`
  the same way `PlayerGamesController.toSummary` does on the
  per-session player id.
- *Alternatives considered:* push the self-side determination into
  the JPQL projection itself (a `CASE WHEN g.whiteUserId = :userId
  THEN 'WHITE' ELSE 'BLACK' END AS selfSide` column). Rejected
  because the JPQL gets noisier without simplifying the controller
  — both approaches need the user id on hand at projection time and
  both need to pick the opponent display name. Keeping the
  projection a thin row-shape and the controller the
  decision-maker keeps the two layers crisp.
- *Why this one:* extends the existing projection rather than
  introducing a second one; reuses the same record on both history
  endpoints; mirrors the per-player-id pattern.

**Decision: keep `GET /api/players/{id}/games` unchanged.**

- *What:* the existing guest-friendly endpoint stays open
  (`.permitAll()`), capped at 50 entries, no pagination params. It
  filters on `games.{white,black}_player_id` (the per-session UUID),
  not the new user FK.
- *Alternatives considered:* deprecate `/api/players/{id}/games` in
  favour of `/api/me/games`. Rejected because the two surfaces
  serve two distinct audiences — guests have no JWT, but they have
  the per-session player id stored in `localStorage` and can still
  query their own history through the player-id path.
- *Why this one:* additive change, zero risk to the existing
  anonymous flow, zero coordination needed with the frontend's
  guest UX.

**Decision: return `Page<MyGameSummary>` directly, document the wire
shape via a schema-only `MyGamesPage` record.**

- *What:* the controller signature is
  `Page<MyGameSummary> getMyGames(...)`. Spring serialises Jackson's
  default `PageImpl` shape — `{ content, totalElements, totalPages,
  size, number, first, last, numberOfElements, empty }` — which is
  the frontend's pre-known typed-client shape. The
  `@ApiResponse(200)` schema points to a nested `MyGamesPage` record
  that mirrors the runtime fields so springdoc renders a properly
  typed Page in Swagger UI rather than a generic Page with `content:
  object[]`.
- *Alternatives considered:* wrap in a custom record returning only
  the fields the frontend uses. Rejected because the standard Spring
  shape is well-known, the frontend's typed client already maps it,
  and the wrapper would only add a translation layer for no benefit.
- *Why this one:* simplest path; matches every other Spring Boot
  REST API that paginates; spec stays honest about the runtime
  shape.

**Decision: trust the security filter chain; no defensive
`Objects.requireNonNull(currentUser)` in `MyGamesController`.**

- *What:* the controller body reads `currentUser.getId()` directly.
  If `currentUser` were null the NPE would propagate as 500.
- *Alternatives considered:* defensive `Objects.requireNonNull`. The
  plan's "controller-layer security guarantee" item floated it.
- *Why this one:* `/api/me/games` is `.authenticated()` in
  `SecurityConfig`. An unauthenticated call surfaces as 401 via
  `AuthEntryPoint` BEFORE the controller method runs. The guarantee
  is the filter chain's, not the controller's; adding a redundant
  check would suggest the contract is unclear when it is not. The
  same posture applies to `MeController.me(User user)` already.

## How this compares to what I know

- **In Scala / Typelevel this would be...** an `http4s` route under
  an `AuthMiddleware[F, User]` that threads the verified `User`
  into the route handler. Pagination shape lives in a
  `case class Page[A](content: List[A], totalElements: Long,
  totalPages: Int, size: Int, number: Int)` — the same five fields
  Spring Data emits, just hand-rolled. The JPQL paginated query is
  doobie's
  ```scala
  sql"... LIMIT $size OFFSET ${page * size}"
    .query[ArchivedGamePlayerView].to[List]
  ```
  + a parallel `COUNT(*)` query; Spring Data derives both from a
  single `@Query` annotation. The `@AuthenticationPrincipal User`
  injection is conceptually what `AuthedRoutes[User, F]` does in
  http4s: by the time the handler runs, the `User` is in scope by
  construction.

- **In Node this would be...** an Express / Fastify route with two
  middlewares: a JWT-verify middleware that attaches `req.user` and
  a 401-or-continue middleware that gates the route. The handler
  reads `req.user.id` and queries Postgres with `LIMIT/OFFSET`. The
  page envelope is hand-rolled around the result + a `COUNT(*)`
  query, same five fields. The wire-format leakage check that
  `GameStateResponse.PlayerView` solves is a `JSON.stringify` worry
  in Node — typically solved by an explicit `toJSON` method on the
  type or a `lodash.pick` at the boundary; the Java solution
  (dedicated wire record) is the same shape mechanically, just
  type-safe instead of runtime-stringified.

## Gotchas / things I learned the hard way

- **Spring 6.1's `HandlerMethodValidationException` replaces
  `ConstraintViolationException` for parameter-level constraint
  failures.** The `@Validated`-on-class + `@Min`/`@Max`-on-param
  combination used to throw `ConstraintViolationException` (Hibernate
  Validator's direct exception). Spring 6.1 wraps it in
  `HandlerMethodValidationException` so the framework can report
  per-parameter violations structurally. `GlobalExceptionHandler`
  needs a handler for the new exception specifically; the
  Hibernate one is kept as a safety-net.
- **The `GameEntity` constructor signature is positional and shared
  across tests.** Adding two new fields (`whiteUserId`,
  `blackUserId`) to the constructor broke
  `GameHistoryRepositoryIT.newGameEntity`. The fix is one-line
  `null, null` insertion. The plan called this scenario out
  ("Existing `GameEntity` ↔ JPQL projection constructor signature
  drift if you add fields to `ArchivedGamePlayerView`"); the
  drift surfaced for `GameEntity`, not the projection, but the
  pattern is the same — a positional constructor with no builder is
  a known refactor friction point. We could later add a
  Lombok-style builder or a copy-with method; portfolio-scope
  trade-off.
- **`UserRepository.deleteAll()` trips on the FK from `games`.**
  `AuthEndpointsIT.cleanUsersTable` previously could delete users
  freely because no `games` row referenced a user. After this
  feature, `MyGamesIT.getMyGames_authenticatedGameCreation_*`
  archives a game with `white_user_id = alice.id`, and if
  `MyGamesIT` doesn't clean up the games table before the next IT
  runs, `AuthEndpointsIT.cleanUsersTable` fails with a constraint
  violation. The cure is an `@AfterEach` on `MyGamesIT` that wipes
  games, restoring the cross-IT invariant. The plan's "Modified
  test: none" line was about the anonymous-flow regression ITs
  (`GameIT` / `RoomIT` / `PlayerGamesIT`); the cleanup hook on the
  new IT is internal to it.
- **Jackson record creator selection is field-count-based.** Adding
  a 2-arg convenience constructor next to the canonical 3-arg
  constructor on a record could in principle confuse Jackson, but
  in practice Jackson scans for a constructor whose param count
  matches the JSON-key count and prefers the canonical one when
  named. We tested this implicitly: the
  `getMyGames_authenticatedGameCreation_populatesUserIdColumns` IT
  round-trips an active game through Redis (created with the
  3-arg constructor) and reads it back for the move sequence.
  Spring's autoconfigured Jackson + records + `Optional<Piece>` all
  survive the cycle.
- **`@Validated` on the class is non-optional for
  `@Min`/`@Max` on `@RequestParam`.** Without it the constraints are
  silently ignored — the request succeeds with bad input. Caught
  via the `getMyGames_invalidPagination_returns400` IT; the first
  draft without `@Validated` returned 200 with an
  out-of-bounds size.

## To dig deeper

- [Spring Data JPA reference — Special parameter handling](https://docs.spring.io/spring-data/jpa/reference/repositories/query-methods-details.html#repositories.special-parameters)
  — `Pageable` + `Page<T>` + derived count queries.
- [Spring Framework reference — Validation, Data Binding, and Type
  Conversion](https://docs.spring.io/spring-framework/reference/core/validation/beanvalidation.html#validation-beanvalidation-spring-method)
  — `@Validated` mechanics, method-level validation.
- [Spring Security reference — @AuthenticationPrincipal](https://docs.spring.io/spring-security/reference/servlet/integrations/mvc.html#mvc-authentication-principal)
  — argument resolver mechanics.
- [Jackson records support](https://github.com/FasterXML/jackson-docs/wiki/JacksonOnRecords)
  — canonical-constructor creator selection, missing-property
  defaults.

## File map

**Created:**

- `src/main/java/io/github/dariogguillen/chess/web/me/MyGamesController.java`
  — new `@RestController` at `/api/me/games`, `@Validated` for
  parameter constraints, `@AuthenticationPrincipal User`, returns
  `Page<MyGameSummary>`. Contains nested `MyGamesPage` schema-only
  record for springdoc.
- `src/main/java/io/github/dariogguillen/chess/web/me/MyGameSummary.java`
  — DTO record `(gameId, roomId, opponentDisplayName, selfSide,
  status, endedAt, moveCount)`.
- `src/test/java/io/github/dariogguillen/chess/web/me/MyGamesIT.java`
  — 7-case IT covering the verification list: 401 without auth,
  empty page, only-own games, anonymous-not-visible, pagination,
  invalid pagination, and the end-to-end write-then-read pinning
  `white_user_id` population.
- `notes/19-auth-my-games.md` — this note.

**Modified:**

- `src/main/java/io/github/dariogguillen/chess/domain/Player.java`
  — added nullable `UUID userId` component + convenience 2-arg
  constructor for guest construction sites; compact constructor
  accepts null on userId.
- `src/main/java/io/github/dariogguillen/chess/persistence/GameEntity.java`
  — added `@Column(name = "white_user_id") UUID whiteUserId` and
  `@Column(name = "black_user_id") UUID blackUserId` plus their
  getters/setters and the extended canonical constructor.
- `src/main/java/io/github/dariogguillen/chess/persistence/GameEntityMapper.java`
  — propagates `Player.userId()` into the entity on `toEntity` and
  back into `Player` on `toDomain`.
- `src/main/java/io/github/dariogguillen/chess/persistence/GameHistoryRepository.java`
  — added `findByUserId(UUID, Pageable) → Page<…>`; extended
  `findByPlayerId` SELECT list to match the new projection shape.
- `src/main/java/io/github/dariogguillen/chess/persistence/ArchivedGamePlayerView.java`
  — added `whiteUserId` and `blackUserId` fields.
- `src/main/java/io/github/dariogguillen/chess/service/GameHistoryService.java`
  — added `findByUser(UUID, Pageable) → Page<…>`; the existing
  `findByPlayer` stays.
- `src/main/java/io/github/dariogguillen/chess/service/RoomService.java`
  — `createRoom` and `joinRoom` now accept an optional `UUID
  currentUserId` parameter and thread it into the new `Player`.
- `src/main/java/io/github/dariogguillen/chess/web/room/RoomController.java`
  — reads `@AuthenticationPrincipal User currentUser` and passes
  `currentUser != null ? currentUser.getId() : null` to the service.
- `src/main/java/io/github/dariogguillen/chess/web/game/GameStateResponse.java`
  — refactored to use a nested `PlayerView` record instead of
  embedding `Player` raw — wire-format isolation from
  `Player.userId`.
- `src/main/java/io/github/dariogguillen/chess/web/game/GameController.java`
  — added a `toPlayerView` helper at the boundary.
- `src/main/java/io/github/dariogguillen/chess/websocket/RoomJoinedEvent.java`
  — `blackPlayer` is now `RoomJoinedEvent.PlayerView` not `Player`;
  added a `PlayerView.of(Player)` factory.
- `src/main/java/io/github/dariogguillen/chess/exception/GlobalExceptionHandler.java`
  — added handlers for `HandlerMethodValidationException` (Spring
  6.1+) and `ConstraintViolationException` (legacy), both → 400
  `VALIDATION_FAILED`.
- `src/test/java/io/github/dariogguillen/chess/persistence/GameHistoryRepositoryIT.java`
  — extended `newGameEntity` to pass `null, null` for the two new
  user-id columns (structural constructor-signature update; not a
  behavioural change).
- `docs/architecture.md` — API contract section adds the new
  endpoint; Authentication section notes the FK columns are now
  active.
- `README.md` — Authentication subsection gains the endpoint
  bullet; static test-count claim updated.

**Not modified (regression locks):**

- `GameControllerIT` — anonymous flow remains green.
- `RoomControllerIT` / `RoomDetailsControllerIT` — anonymous flow
  remains green.
- `PlayerGamesControllerIT` — the guest-history endpoint stays
  unchanged.
- `AuthEndpointsIT` — auth issuance untouched.
- `RoomLifecycleIT` — `RoomJoinedEvent` shape stayed
  Java-source-compatible (accessor names match `Player`), so the
  STOMP IT keeps green without modification.

**Cross-repo:** **required**. The new `GET /api/me/games` endpoint is
  the cross-repo payoff of the auth bundle; the frontend's "my
  games" view consumes it. Page-shape contract: standard Spring
  Data envelope, pre-known to the frontend's typed client. The
  existing `/api/players/{id}/games` stays for guests; the frontend
  switches between the two based on auth state.
