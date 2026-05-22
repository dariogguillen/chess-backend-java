# Feature 09 — Game history in Postgres

**Feature ID:** `postgres-game-history` (from `feature_list.json`)

**Status:** in progress

---

## What we built

A durable archive layer in Postgres for games that have reached a terminal
status (`CHECKMATE`, `STALEMATE`, `DRAW`, `ABANDONED`). The Redis active-state
store from feature 8 stays unchanged — it is still the source of truth for
ongoing games — but every move that flips a game terminal now also writes
the full game (denormalised players, starting and final FEN, status, ordered
move history) to Postgres before the Redis side commits. A new endpoint
`GET /api/players/{id}/games` reads the archive, newest first, capped at
50 entries.

UUID-typed ids flow end-to-end — DB column type, JPA entity field, domain
record, service signature, REST DTO. The wire format on the JSON side is
unchanged (Jackson serialises `UUID` as a plain string), so no frontend
coordination is required.

## Java / Spring concepts that appear

- **`@Entity` vs `record`.** JPA needs a mutable class with a no-arg
  constructor and writable fields it can populate via reflection. Records
  are immutable by construction (the canonical constructor with the
  declared components is the only way to build one), so a record cannot
  satisfy Hibernate's contract for an entity-managed instance. The
  persistence layer therefore defines `GameEntity` and `MoveEntity` as
  classic mutable classes with package-private setters, a
  `protected` no-arg constructor (for JPA's reflective hand), and a
  `GameEntityMapper` `@Component` as the only ingress/egress between the
  immutable domain `Game` record and its mutable entity shadow. Outside
  the `persistence` package no code can mutate the entity. See
  [Hibernate ORM — Java records and embeddables](https://docs.jboss.org/hibernate/orm/6.4/userguide/html_single/Hibernate_User_Guide.html#embeddable-instantiator).
- **`@IdClass` for composite keys.** `MoveEntity`'s primary key is
  `(game_id, move_idx)`. JPA exposes two ways to model that: `@EmbeddedId`
  (one wrapper field that *is* the key) or `@IdClass` (each PK column is
  its own `@Id` field on the entity, and a separate identifier class
  mirrors them). The `@IdClass` flavour reads more naturally when the PK
  fields are also natural query parameters — you write
  `entity.getMoveIdx()`, not `entity.getId().getMoveIdx()`. Modern
  Hibernate accepts a Java record as the id class as long as it
  implements `Serializable` and its component names match the entity's
  `@Id` fields; `MoveEntityId` is the smallest such record. See
  [Jakarta Persistence — Composite primary keys](https://jakarta.ee/specifications/persistence/3.1/jakarta-persistence-spec-3.1.html#a1057).
- **`@Enumerated(EnumType.STRING)`.** Hibernate's default for an
  unannotated enum column is `ORDINAL` — it stores the *declaration
  index* of the constant as an integer. That is a famous footgun: insert
  a new `GameStatus` value anywhere except the end, and every existing
  row silently re-interprets to a different constant on read. `STRING`
  stores the name (`"CHECKMATE"`), so reorderings are free and additions
  cost nothing on existing rows. The trade-off is ~10 bytes per row,
  which is irrelevant for our volumes. See
  [JPA Buddy — `@Enumerated`](https://jpa-buddy.com/blog/best-practices-and-common-pitfalls/).
- **`@OneToMany(mappedBy = ..., cascade = ALL, orphanRemoval = true)`.**
  `mappedBy = "game"` declares `MoveEntity#game` as the *owning side* of
  the relationship — the FK column lives there. `cascade = ALL` means
  saving the parent saves the children; `orphanRemoval = true` means
  removing a `MoveEntity` from the parent's list deletes its row. The
  combination matches our archive shape: a `GameEntity` plus its moves
  is a single unit, and re-archiving an existing id (the idempotent
  branch) replaces both the row and its children atomically rather than
  merging them side-by-side. See
  [Spring Data JPA Reference — Persisting Entities](https://docs.spring.io/spring-data/jpa/reference/jpa/entity-persistence.html).
- **JPQL constructor projection (`SELECT new <fqn>(...)`).** The
  history query bypasses entity loading entirely and constructs an
  immutable `ArchivedGamePlayerView` record per row, with `SIZE(g.moves)`
  translated to a correlated `COUNT(*)` subquery. That is the right
  shape for a list endpoint: the wire DTO carries the move count but
  not the moves themselves, and the projection produces exactly the
  data shape the controller hands to Jackson. It also dodges the
  `LazyInitializationException` we would otherwise see when the
  controller dereferences a lazy collection outside the service's
  transaction (the app runs with `spring.jpa.open-in-view: false`).
- **`@Transactional` propagation.** `GameHistoryService.archive` is
  annotated `@Transactional`. The service is invoked by
  `GameService.applyMove` from inside a `GameStore.compute` lambda that
  is *not* itself transactional (Redis is not a JTA resource).
  Spring's default `Propagation.REQUIRED` starts a new transaction on
  entry to `archive` and commits before returning to the caller — so
  the Postgres write is atomic with respect to itself, but it is *not*
  joined to the Redis write. That is the safe ordering we want:
  Postgres commits first; if it throws, the Redis side never sees the
  terminal state. See
  [Spring Framework — Transaction propagation](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html).
- **`ddl-auto: validate` + Flyway.** Flyway applies SQL migrations at
  boot; Hibernate's `validate` mode then verifies the JPA model
  matches the actual schema. A column that exists in the entity but
  not in the migration (or vice versa) fails startup loudly. The
  alternative `update` looks convenient but silently drifts — it can
  add columns the migrations never see, and a future
  `flyway-from-scratch` run on a clean DB then produces a different
  shape than the dev box. `validate` keeps SQL as the single source
  of truth.
- **`spring.jpa.open-in-view: false`.** The Spring Boot default is
  `true`: the JPA session stays open for the whole HTTP request, which
  masks lazy-loading bugs in the view layer (Jackson can pull lazy
  associations *during serialization*, well outside the service's
  intended transaction boundary). Off is the correct setting; the
  warning Spring Boot logs on startup when you leave it on (since
  3.1) is the official "you probably want to turn this off" signal.
- **Testcontainers `PostgreSQLContainer` + `@ServiceConnection`.** The
  test container is declared once in `TestcontainersConfiguration.java`
  with `@ServiceConnection`, which auto-wires `spring.datasource.*` to
  the container's randomised JDBC URL. Every IT that imports the
  configuration boots the full stack against a real Postgres in a few
  seconds; Flyway applies `V1__create_game_history.sql` on context
  startup, and the entity manager validates against the resulting
  schema. No H2, no `@DataJpaTest` substitution. See
  [Testcontainers — Postgres module](https://java.testcontainers.org/modules/databases/postgres/).

## Decisions taken

**UUID end-to-end: DB column + JPA entity + domain record.**

- Decision: `id`, `white_player_id`, `black_player_id` on `games` and the
  FK `game_id` on `moves` are native Postgres `uuid` columns. The JPA
  fields are `java.util.UUID`. The domain records (`Player.id`, `Game.id`)
  are `java.util.UUID` too. The REST controller paths and headers bind to
  `UUID` directly via Spring's default `String→UUID` converter.
- Alternatives considered: (a) `TEXT` columns with `String` everywhere
  (the round-1 shape); (b) `UUID` column + `String` domain field +
  `@JdbcTypeCode(SqlTypes.UUID)` on the entity to bridge the two; (c)
  per-component `AttributeConverter<String, UUID>`.
- Why: Postgres `uuid` is 16 bytes vs 36 for `TEXT`, and the planner is
  better with native types. Carrying `UUID` end-to-end means the boundary
  catches bad input at parse time (a malformed path id throws
  `MethodArgumentTypeMismatchException`, mapped to `400 MALFORMED_REQUEST`)
  rather than letting it travel inside as an arbitrary string until SQL
  rejects it. Option (b) was the round-1 plan — it turns out
  `@JdbcTypeCode(SqlTypes.UUID)` on a Java `String` field is **not** a
  working configuration: Hibernate has no built-in `String ↔ uuid` JDBC
  binding strategy. Either you change the Java type, or you write an
  `AttributeConverter`, which is just option (c) with extra ceremony.
  The cleanest answer is "let the type be `UUID` everywhere it actually
  is one." `Room.id` and `Game.roomId` stay `String` because the room id
  is the 6-char short code, **not** a UUID.

**No `players` table — snapshot semantics for player info.**

- Decision: `games` carries `white_player_id`, `white_display_name`,
  `black_player_id`, `black_display_name` directly. No `players` table.
- Alternatives considered: a `players` table FK'd from `games`.
- Why: two reasons, in order of importance. **Snapshot semantics first**
  — the display name at archive time is the audit-correct value. This
  is the same shape Lichess uses for game records, GitHub uses for
  commit author names, and Steam uses for friend graph history: when a
  user renames themselves, past artifacts keep the name they had at the
  time. A future rename of "Alice" to "Carol" must not retroactively
  rewrite the games Alice played. **No real Player identity today**
  is the secondary reason — guests have ephemeral UUIDs, no email, no
  auth, no rating, no created_at, no rows pinned to the player id.
  A `players` table would store nothing the `games` table doesn't
  already carry. When auth lands, `V2__create_players.sql` extracts
  distinct UUIDs into a new table with `kind='HISTORICAL_GUEST'`,
  adds the FK, and the in-flight games table grows a nullable column;
  the migration path is well-defined and obvious.

**Separate `games` and `moves` tables, not a `jsonb` move history.**

- Decision: separate `games` and `moves` tables, the latter
  cascade-deleted from the former.
- Alternatives considered: a single `games` table with a `jsonb`
  column for the move history.
- Why: the relational layout exercises the JPA features this
  portfolio backend is meant to demonstrate (`@OneToMany`, composite
  keys, projections) and keeps the door open to move-level
  queries ("show me all promotions to a knight") without ever having
  to migrate jsonb into rows. jsonb would be the Postgres-idiomatic
  shortcut and the right call in production if move-level queries
  were not on the table — for our scope, the relational version is
  more honest about what is being learned.

**Synchronous archive inside `applyMove`, not fire-and-forget.**

- Decision: `GameHistoryService.archive(updated)` runs inside the
  `GameStore.compute` lambda before the lambda returns. If Postgres
  throws, the Redis-side write is skipped, the move request fails
  with 500, and Redis still holds the previous (non-terminal) state.
- Alternatives considered: (a) an `@Async` archive that returns
  immediately; (b) a Spring event that triggers the archive after
  the REST response is sent; (c) a Postgres outbox row written
  inside the same Redis lambda, processed by a background worker.
- Why: a portfolio backend gains nothing from the complexity of (a)
  / (b) / (c) at our volumes — terminal games happen at most every
  few minutes per active room, and a synchronous Postgres write is
  in the low-millisecond range. The synchronous shape is also the
  one that makes the failure mode *visible*: a misconfigured
  Postgres connection breaks the move with a 500 the user can
  diagnose, instead of an archive that silently disappears. If we
  ever need decoupling, the outbox pattern is the right upgrade and
  the schema stays the same.

**JPA entities as mutable classes, not records.**

- Decision: `GameEntity` and `MoveEntity` are classic mutable
  classes with package-private setters.
- Alternatives considered: keep them as records and rely on
  Hibernate 6.x's record support.
- Why: Hibernate's record support covers *immutable views* of
  entities and embeddables in narrow read-only paths; it does not
  cover entity-managed instances that the persistence context has
  to populate via reflection across the lifecycle. Trying to bend
  a record into that role works in toy demos and breaks on real
  features like `orphanRemoval` and dirty-checking. The cleaner
  separation is "domain records, persistence classes, mapper at
  the boundary" — that is what `docs/conventions.md` already
  prescribes for DTOs vs entities, and we extend it to the
  archive shape here.

**`@IdClass(MoveEntityId)` over `@EmbeddedId`.**

- Decision: composite key declared by listing both PK fields with
  `@Id` on `MoveEntity` and a separate `MoveEntityId` record as
  `@IdClass`.
- Alternatives considered: an `@EmbeddedId` wrapper.
- Why: with `@IdClass` the entity's accessors are flat
  (`move.getMoveIdx()`), which is how callers naturally think about
  the data. With `@EmbeddedId` every access goes
  `move.getId().getMoveIdx()`, which is more boilerplate at every
  site and only pays off if the id type has its own behaviour to
  reuse. Modern Hibernate accepts a record as the id class with no
  ceremony, so the verbosity tax that historically pushed people
  toward `@EmbeddedId` is gone.

**`@Enumerated(STRING)` over `@Enumerated(ORDINAL)`.**

- Decision: store the enum constant's name as TEXT.
- Alternatives considered: store the declaration index as INTEGER.
- Why: `ORDINAL` is a well-known footgun — inserting a new
  `GameStatus` constant anywhere but the end silently re-interprets
  every existing row. The ~10 bytes per row that `STRING` costs is
  negligible at our volumes, and the column stays human-readable in
  `psql`.

**`ddl-auto: validate` over `update`.**

- Decision: Flyway owns the schema; Hibernate's `validate` mode
  verifies the JPA model against it at boot.
- Alternatives considered: `update` (Hibernate applies inferred
  ALTER TABLE statements on startup), `none` (no validation).
- Why: `update` makes the *running JVM* the source of truth for
  the schema, which silently diverges from what a clean Flyway run
  produces. `none` gives up the cross-check entirely. `validate`
  keeps Flyway authoritative and catches misalignment loudly — the
  app refuses to start if an entity references a column the
  migration forgot.

**`spring.jpa.open-in-view: false`.**

- Decision: explicit override of Spring Boot's default `true`.
- Alternatives considered: leave the default on.
- Why: open-in-view is the anti-pattern Spring Boot warns about on
  startup since 3.1 ("spring.jpa.open-in-view is enabled by
  default..."). Off forces every lazy access to happen inside a
  `@Transactional` boundary, which is what we want. The history
  endpoint never needs lazy navigation because the JPQL projection
  pre-fetches the move count in the same query.

**No `CHECK` constraint on `status`.**

- Decision: `status VARCHAR(20) NOT NULL` with no DB-side enum check.
- Alternatives considered: `CHECK (status IN ('CHECKMATE', ...))`.
- Why: JPA's `@Enumerated(STRING)` enforces the alphabet on every
  write path (the only producer of the column), and a DB check
  would have to be DROP + ADD on every future `GameStatus`
  addition. The defensive value of a redundant check is low; the
  maintenance cost is non-zero. We accept the trade and document
  it.

**No Postgres ENUM type for `status` / `promotion`.**

- Decision: `VARCHAR + @Enumerated(STRING)` on the JPA side. No
  `CREATE TYPE ... AS ENUM`.
- Alternatives considered: a Postgres ENUM type plus
  `@JdbcTypeCode(SqlTypes.NAMED_ENUM)` (Hibernate 6.x) or an
  `AttributeConverter` to bridge.
- Why: a Postgres ENUM is a different type than `VARCHAR` — adding a
  value requires `ALTER TYPE ... ADD VALUE` (one-way, can't rename or
  remove cleanly), and the Hibernate-to-Postgres-ENUM mapping needs
  per-column annotations on top of `@Enumerated`. `@Enumerated(STRING)`
  on a `VARCHAR` already enforces enum validity from the only client
  writing the DB, and is the canonical Spring/JPA pattern (Lichess,
  GitHub, and the official Spring Data JPA examples all do this). The
  ENUM type buys nothing once the writer is already enforcing the
  alphabet.

**Bounded `VARCHAR(N)` with `@Column(length = N)` + `ddl-auto: validate`.**

- Decision: every text column has an explicit length on both sides
  (SQL `VARCHAR(N)` and JPA `@Column(length = N)`). Squares are
  `VARCHAR(2)`, promotion `VARCHAR(10)`, status `VARCHAR(20)`, display
  names `VARCHAR(100)`, FEN strings `VARCHAR(100)`, room code
  `VARCHAR(6)`.
- Alternatives considered: `TEXT` (unbounded), which is what the
  round-1 column type chose.
- Why: the lengths are part of the contract, not just a hint. `validate`
  on startup checks `column type + length` against the schema, so a
  drift between the entity's declared bound and the column's bound
  fails boot. Bounded columns also surface bugs upstream (a 300-char
  garbage display name fails the insert immediately instead of
  poisoning the row), and document the expected size at the schema
  level for anyone reading SQL.

**Why `CHAR(2)` was rejected for squares.**

- Decision: `from_square` and `to_square` are `VARCHAR(2)`, not
  `CHAR(2)`, even though every value is exactly two characters.
- Alternatives considered: `CHAR(2)`.
- Why: Hibernate maps a Java `String` field to JDBC `Types.VARCHAR`,
  while Postgres reports `CHAR(N)` columns as JDBC `Types.CHAR`. The
  `ddl-auto: validate` check compares the codes and fails boot on the
  mismatch — even though the on-disk bytes are equivalent. Bridging
  the mismatch would require a `columnDefinition` override or a custom
  `AttributeConverter`. `VARCHAR(2)` gives the same storage profile
  (Postgres treats short `VARCHAR(N)` and `CHAR(N)` identically
  on-disk for fixed-width content) without the type-code dance.

**LAN, not SAN, for moves.**

- Decision: a `Move` is `(from, to, Optional<Piece> promotion)` — the
  Long Algebraic Notation shape used by UCI engines and chesslib.
- Alternatives considered: Standard Algebraic Notation strings
  (`"Nxe5+"`, `"O-O"`, `"e4"`) as the move type.
- Why: LAN is unambiguous by construction — the from-square plus the
  to-square already fully describes the move, with promotion as the
  only intra-move ambiguity (a pawn reaching the back rank can
  promote to four different pieces; nothing else in chess has this
  shape). Modeling promotion as `Optional<Piece>` makes the
  ambiguity explicit at the type level — the canonical
  smart-constructor pattern. Check, checkmate, and stalemate are
  **post-move game state**, not properties of the move itself: a
  move "delivers check" only relative to a starting position, and
  encoding that in the move would denormalise the game state. SAN
  is a presentation concern (it disambiguates against the current
  board on demand), derivable from LAN + board whenever a UI wants
  it; carrying it in the domain would force every persistence layer
  to either store both forms (duplication) or recompute SAN on
  every read (work for no value).

**No `players` table — `404` is not used for unknown players.**

- Decision: `GET /api/players/{id}/games` with an unknown id
  returns `200` with `[]`.
- Alternatives considered: `404` with `PLAYER_NOT_FOUND`.
- Why: there is no `players` registry to compare the id against
  — guests are anonymous UUIDs minted at room creation. The
  honest answer to "list this player's games" for an unknown id
  is "no games found", not "this player doesn't exist". The
  empty-list answer also keeps the frontend code simpler (no
  special branch for 404 vs empty).

**No game-detail endpoint, no pagination.**

- Decision: explicitly out of scope for this feature.
- Alternatives considered: ship them inline.
- Why: the acceptance criterion says "list", not "detail", and the
  hard cap of 50 entries is enough for portfolio scope. The
  `moves` table already carries the data a future detail endpoint
  would need; the schema is forward-compatible.

## How this compares to what I know

**In Scala / Typelevel this would be...**

- **`@Entity` mapping** ≈ a doobie/skunk row `case class` plus a
  `Read[T]` / `Write[T]` typeclass instance (`doobie.Read`
  derivation, or a manually-written `Get`/`Put` pair). The Scala
  version is *type-driven* — the compiler resolves the codec at
  the call site of `query.to[List]`. JPA is *annotation-driven* —
  Hibernate inspects the class at runtime and assembles the
  mapping. Both achieve the same end (case class ↔ row); JPA
  pays the cost in startup reflection, Scala in macro/derivation
  compile time. Records here play the role of `case class`es on
  the domain side; entities are the parallel mutable shape we
  keep out of the domain.
- **`JpaRepository` derived queries / JPQL** ≈ Quill quoted DSL,
  with the difference that Quill produces SQL at compile time and
  Spring Data assembles the JPQL → SQL at startup. Both bind a
  type to a query without you writing the SQL by hand for the
  simple cases; both let you drop to a hand-written query for the
  cases the derivation doesn't cover (we did exactly that with
  the JPQL constructor projection because derived names can't
  express `OR` over two columns cleanly).
- **`@Transactional` boundaries** ≈ `Transactor[F]` /
  `Resource[F, Connection]` in doobie. Spring's annotation hides
  the boundary in a proxy; the doobie shape makes it explicit at
  the call site. Both serialize per-transaction effects at the
  same logical layer (service / use-case). The Spring version is
  declarative and looks lighter; the doobie version is explicit
  and looks safer. Personal taste mostly — production-grade
  monolithic Spring services I've seen always wrap state changes
  in `@Transactional`, just as production-grade Scala services I've
  written always thread a `Transactor[F]`.
- **`UUID` end-to-end** ≈ a `Meta[UUID]` instance in doobie or a
  `Get[UUID]`/`Put[UUID]` pair in skunk. The Scala version composes
  via implicit resolution — you declare the row type as
  `case class GameRow(id: UUID, ...)` and the codec is summoned at
  the call site. JPA's version is annotation-driven and relies on
  Hibernate's built-in `UUID ↔ uuid` binding, which Just Works once
  the field type is correct. Same idea: a typed boundary at every
  layer (DB, model, service signature), no untyped string in
  between.
- **Snapshot semantics for the player display name** ≈ event-sourced
  systems that embed the actor's display state in the event payload
  at emission time (rather than dereferencing the actor by id from
  some live registry on read). The denormalisation looks like
  duplication, but it is the audit-correct shape: past events are
  immutable artifacts of "what was true when this happened".
- **Flyway** is the same product in Scala. Both ecosystems can
  also use doobie's `MigrateF` recipe, but in practice the Java
  CLI / Boot autoconfig is what teams reach for in both worlds.
- **JPQL constructor projection** ≈ a `Read[ArchivedGamePlayerView]`
  + a `query.to[List]` in doobie, where the row gets read directly
  into the case class. The Java version writes `SELECT new
  <fqn>(...)` because JPQL has no row-derivation; the Scala version
  reads `sql"select g.id, g.room_id, ...".to[ArchivedGamePlayerView]`
  and the codec does the work.

**In Node this would be...**

- An `ORM`-flavoured library like TypeORM with `@Entity` /
  `@PrimaryColumn` decorators that *look* almost identical to the
  JPA annotations, or a query-builder layer like Kysely / Prisma
  that prefers schema-from-SQL with a typed query API. The
  declarative-mapping vs query-builder split is exactly the same
  in Node as in Java. The migration tool — `node-pg-migrate` or
  Flyway in a Node sidecar — is essentially the same product wearing
  different clothes.

## Gotchas / things I learned the hard way

- The Spring Data `Limit` type lives under
  `org.springframework.data.domain.Limit`, separate from
  `Pageable` / `Page`. It accepts an `int` and is the
  ergonomic way to cap a result set without paging; I almost
  reached for `Pageable` and a `findAll(Pageable)` overload before
  remembering that `Limit` is exactly the shape I needed.
- `SIZE(g.moves)` in JPQL translates to a correlated subquery,
  not a `JOIN ... GROUP BY`. That is what we want here (we don't
  want to materialise the moves themselves), but the implicit
  translation is worth knowing — the query plan for many history
  rows could be checked with `EXPLAIN` if it ever becomes a hot
  path.
- `@OneToMany(orphanRemoval = true)` makes `setMoves(newList)` on
  a transient (not-yet-persistent) entity work as expected, but on
  a managed entity Hibernate prefers `clear() + addAll()` on the
  existing collection so it can track the diff. Our archive path
  always builds fresh entities, so the simpler `setMoves` works,
  but I documented the caveat in `GameEntity#setMoves`.
- `spring.jpa.open-in-view` is on by default *and* Spring Boot
  logs a "you should turn this off" warning on every startup
  since 3.1 — the kind of warning that becomes background noise
  fast. Turning it off this round catches the
  `LazyInitializationException` upfront and the projection-based
  query design follows naturally.

## To dig deeper

- [Spring Data JPA — Reference Documentation](https://docs.spring.io/spring-data/jpa/reference/)
- [Hibernate ORM 6 User Guide](https://docs.jboss.org/hibernate/orm/6.4/userguide/html_single/Hibernate_User_Guide.html)
- [Flyway — Concepts](https://documentation.red-gate.com/flyway/flyway-concepts)
- [Vlad Mihalcea — High-Performance Java Persistence (blog)](https://vladmihalcea.com/tutorials/hibernate/) — the JPA + Hibernate footgun catalog
- [Spring Boot — `spring.jpa.open-in-view` discussion (#7107)](https://github.com/spring-projects/spring-boot/issues/7107)

## File map

- `src/main/resources/db/migration/V1__create_game_history.sql` — Flyway
  migration creating `games`, `moves`, and the two `(player_id, ended_at)`
  indexes.
- `src/main/resources/application.yml` — adds `spring.jpa.*` and
  `spring.flyway.*` properties (`ddl-auto: validate`, `open-in-view:
  false`, `baseline-on-migrate: false`).
- `src/main/java/io/github/dariogguillen/chess/persistence/GameEntity.java`
  — `@Entity` for `games`, mutable, package-private setters.
- `src/main/java/io/github/dariogguillen/chess/persistence/MoveEntity.java`
  — `@Entity` for `moves`, composite key via `@IdClass`.
- `src/main/java/io/github/dariogguillen/chess/persistence/MoveEntityId.java`
  — `record` implementing `Serializable`, the `@IdClass` for `MoveEntity`.
- `src/main/java/io/github/dariogguillen/chess/persistence/GameHistoryRepository.java`
  — `JpaRepository<GameEntity, String>` plus the JPQL projection query.
- `src/main/java/io/github/dariogguillen/chess/persistence/ArchivedGamePlayerView.java`
  — record carrying the per-row projection (id, players, status, ended_at,
  moveCount).
- `src/main/java/io/github/dariogguillen/chess/persistence/GameEntityMapper.java`
  — `@Component` mapping domain `Game` ↔ `GameEntity`, with the injected
  `Clock` for `ended_at`.
- `src/main/java/io/github/dariogguillen/chess/service/GameHistoryService.java`
  — `@Transactional archive(Game)` + `@Transactional(readOnly = true)
  findByPlayer(String)`.
- `src/main/java/io/github/dariogguillen/chess/service/GameService.java`
  — modified: new `GameHistoryService` dependency, archive call inside the
  `GameStore.compute` lambda on terminal status (before the Redis write
  commits).
- `src/main/java/io/github/dariogguillen/chess/web/game/PlayerGamesController.java`
  — `GET /api/players/{id}/games` with `@Tag` / `@Operation` /
  `@ApiResponse` annotations.
- `src/main/java/io/github/dariogguillen/chess/web/game/PlayerGameSummary.java`
  — wire-format record for the response.
- `docs/architecture.md` — new "Game history in Postgres" subsection.
- `src/test/java/io/github/dariogguillen/chess/persistence/GameEntityMapperTest.java`
  — unit: `Optional<Piece>` ↔ nullable string round-trip, move order,
  scalar field copy.
- `src/test/java/io/github/dariogguillen/chess/persistence/GameHistoryRepositoryIT.java`
  — IT: save/find round-trip with moves; `findByPlayerId` covers white,
  black, unknown, limit, and the projection's move count.
- `src/test/java/io/github/dariogguillen/chess/service/GameHistoryServiceIT.java`
  — IT: archive persists, archive is idempotent on the same id,
  `findByPlayer` finds games regardless of side, unknown player ⇒ empty
  list, promotion is preserved.
- `src/test/java/io/github/dariogguillen/chess/web/game/PlayerGamesControllerIT.java`
  — IT: unknown player ⇒ 200 with `[]`; player with games ⇒ summaries,
  newest first, role and opponent name correct.
- `src/test/java/io/github/dariogguillen/chess/web/game/GameControllerIT.java`
  — extended: the Fool's Mate REST flow now also asserts the game
  appears in Postgres with 4 moves and status `CHECKMATE`.
