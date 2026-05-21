## Feature 08 — Active state in Redis

**Feature ID:** `redis-active-state` (from `feature_list.json`)

**Status:** in progress

---

## What we built

Migrated the active-state storage of `Room` and `Game` records from a JVM-local
`ConcurrentHashMap` to Redis, behind the existing `RoomStore` / `GameStore` seam.
The service, controller, and WebSocket layers were not touched: this is a pure
adapter swap. Every successful write sets (or refreshes) a 24-hour TTL on the
key, so a game that nobody interacts with for 24 hours self-cleans without any
background sweeper. The `compute(id, fn)` atomic read-modify-write contract is
preserved by a process-local per-key lock (`StripedKeyLock`), which is enough
for the single-instance backend we deploy.

## Java / Spring concepts that appear

- **`RedisTemplate<K, V>` vs. `StringRedisTemplate` vs. `@RedisHash` +
  `CrudRepository`.** Spring Data Redis offers three flavors of access. The
  `CrudRepository` flavor (`@RedisHash` on the value class + a `JpaRepository`-
  style interface) is the highest level and the one most tutorials reach for,
  but it requires the value type to be a mutable bean — JPA-style getters and
  setters, a no-arg constructor, and a settable id field. Our domain types
  are **immutable records** with compact constructors enforcing invariants;
  forcing them into the `@RedisHash` shape would mean either dropping the
  records or duplicating the model into a "Redis entity" layer just to
  serialize. `RedisTemplate<K, V>` is one level lower: it gives you typed
  `opsForValue() / opsForHash() / opsForList()` operations against
  serializer-chosen value bytes, with no requirement on the value type
  beyond "Jackson can serialize it". That fits records out of the box. The
  third flavor, `StringRedisTemplate`, is the same shape as `RedisTemplate`
  with both sides bound to `String` — useful for ad-hoc CLI-style work, but
  it puts all of the JSON encoding on you.
  See [Spring Data Redis reference — Working with Objects through `RedisTemplate`](https://docs.spring.io/spring-data/redis/reference/redis/template.html).

- **`Jackson2JsonRedisSerializer<T>` vs. `GenericJackson2JsonRedisSerializer`.**
  Both turn an arbitrary POJO/record into JSON bytes. The generic one preserves
  Java type information by adding a `@class` property to every JSON document, so
  it can deserialize back to the original concrete type when the target type is
  unknown (e.g., heterogeneous values in the same template). That is great if
  you actually have polymorphism; it is noise if you do not. Our value types are
  closed records with no subtyping, so per-type serializers (one
  `Jackson2JsonRedisSerializer<Room>` bound to a
  `RedisTemplate<String, Room>`, one for `Game`) keep `redis-cli GET room:X`
  output to the bare domain shape and avoid Jackson's polymorphic-deserialization
  attack surface as a free byproduct.
  See [Spring Data Redis reference — Object Mapping](https://docs.spring.io/spring-data/redis/reference/redis/redis-repositories/mapping.html).

- **Spring Boot's autoconfigured `ObjectMapper` and Java 8 datatypes.** Spring
  Boot registers `jackson-datatype-jdk8` and `jackson-datatype-jsr310` on the
  context `ObjectMapper` bean. `jdk8` is what serializes `Optional<T>` as either
  the inner value or `null`, which matters for us because `Move` carries an
  `Optional<Piece>` (the optional promotion target). Reusing the autoconfigured
  mapper instead of `new ObjectMapper()` in `RedisConfig` gives the Redis JSON
  the same shape as the HTTP JSON on the REST side — same `Optional` handling,
  same enum encoding, same instant format — without us having to repeat the
  module registration. The downside is that any future global Jackson tweak
  will affect Redis serialization too; the upside is that consistency between
  surfaces is the default.

- **`@ConfigurationProperties` with `Duration`.** `@ConfigurationProperties`
  binds a YAML / env namespace into a typed Java record. For `Duration`, Spring
  uses *relaxed binding*: `24h`, `PT24H`, `86400s`, `1d` all parse to the same
  value. We register the properties via `@EnableConfigurationProperties` on
  `RedisConfig` and inject the resulting record into the two stores; the TTL
  is then a `Duration` field, not a string we re-parse, and Spring will fail
  context startup if the value is unparseable.
  See [Spring Boot reference — Type-safe Configuration Properties](https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.typesafe-configuration-properties).

- **`opsForValue().set(key, value, ttl)` and the "refresh on every write"
  pattern.** Redis exposes `SET key value EX|PX <ttl>` as a single command,
  atomic with the value write. Spring's `opsForValue().set(K, V, Duration)`
  goes through that path, so there is no "write the value then set the TTL"
  race. Every `save` calls this overload, so any successful mutation
  automatically extends the lease. Reads use plain `GET`, which does not touch
  the TTL — a key that no one writes to expires on its own. There is no
  separate refresh code path because we never want one.

- **`@TestPropertySource` for IT-scoped property overrides.**
  `RedisTtlIT` overrides `chess.redis.active-state-ttl=30s` so the test can
  watch the TTL count down and observe the refresh after a `save`. The override
  is scoped to that test class only; the production default of `24h` applies
  everywhere else. It is the same mechanism the production deployment uses via
  `CHESS_REDIS_ACTIVE_STATE_TTL` — the binding is exercised end-to-end through
  `RedisActiveStateProperties`.

## Decisions taken

**`RedisTemplate` + per-type `Jackson2JsonRedisSerializer<T>` over `@RedisHash` +
`CrudRepository`.**

- Decision: declare one `RedisTemplate<String, Room>` and one
  `RedisTemplate<String, Game>` in `RedisConfig`, each with the autoconfigured
  `ObjectMapper` wrapped in a per-type `Jackson2JsonRedisSerializer<T>`.
- Alternatives considered: (a) `@RedisHash(Room)` on the record with a
  `RoomRepository extends CrudRepository<Room, String>`; (b)
  `GenericJackson2JsonRedisSerializer` on a single `RedisTemplate<String, Object>`.
- Why: (a) does not work for records — `@RedisHash` requires a mutable bean
  shape, and our records enforce invariants we do not want to drop. (b) bakes
  `@class` Java-type metadata into every JSON document; that is wasted bytes
  when there is no polymorphism to preserve, makes `redis-cli` output noisy,
  and broadens the deserialization attack surface for no benefit.

**Process-local `StripedKeyLock` over `WATCH/MULTI/EXEC` or Lua.**

- Decision: a `ConcurrentHashMap<String, ReentrantLock>` keyed by store id,
  used as a per-key mutex around the `read → apply function → write` block in
  `compute`. Locking lives in-process; Redis sees plain `GET` / `SET` /
  `DEL` calls.
- Alternatives considered: (a) Redis-side optimistic concurrency via
  `WATCH` + `MULTI` + `EXEC` with a retry loop on `null`-exec; (b) a Lua
  script that performs the entire `read → compute → write` atomically inside
  Redis; (c) a distributed-lock library (Redisson, Redlock).
- Why: the production deployment is single-instance (one EC2 host, one
  backend container — see `docs/architecture.md` "Deployment artifact"). A
  JVM-local lock is constant-time, retry-free, easy to read, and matches the
  semantics that the old `ConcurrentHashMap.compute` inherited from the JDK.
  (a) would force a retry loop and a CAS mental model on every consumer; (b)
  would push the move-application logic out of Java into Lua, fighting the
  type safety we wanted to preserve; (c) brings a heavyweight dependency for
  a problem we do not have. If we ever go multi-instance, the upgrade is to
  swap the lock implementation behind the same `StripedKeyLock get(String)`
  contract — none of the callers change.

**24-hour TTL, refreshed on every write, never on read.**

- Decision: every `save` calls `opsForValue().set(key, value, ttl)` with `ttl`
  bound from `chess.redis.active-state-ttl` (default 24h). `findById` is a
  plain `GET` with no TTL touch.
- Alternatives considered: (a) no TTL at all, plus a periodic sweeper that
  evicts abandoned rooms; (b) "refresh on read" so a noisy-but-idle observer
  can keep a key alive.
- Why: (a) requires a scheduler we do not otherwise need and turns "abandoned"
  into a fuzzy concept (the sweeper has to define it). The TTL approach is
  declarative — Redis knows when the lease ends. (b) would make spectators
  capable of perpetually extending a game with no actual play happening, which
  is not the behaviour we want. A 24h window is long enough that real games
  (and short interruptions) never expire and short enough that abandoned ones
  self-clean within a day.

**Cross-store invariant stays in the service layer.**

- Decision: "a game exists iff its room is `ACTIVE`" continues to live where
  it always did — inside `RoomService.joinRoom`, which calls
  `gameStore.save(game)` inside the `roomStore.compute(roomId, ...)` block.
  No new code in this feature.
- Alternatives considered: a transactional wrapper that batches the two
  writes (via Redis `MULTI/EXEC` or a Lua script) so that a crash between
  them is impossible.
- Why: the worst case is a room marked `ACTIVE` with no game, which the
  existing recovery code already tolerates and which feature 10
  (`disconnect-handling`) plans to formalise. Adding Redis-side
  transactionality across two stores would be a structural change for a
  failure window that is already covered by upcoming work.

**`ViewerCountTracker` stays in-memory — explicitly out of scope.**

- Decision: not touched in this feature.
- Why: it is bound to live STOMP sessions on the current JVM, which already
  cannot survive a restart. A Redis-backed viewer counter would imply we can
  recover viewer counts across restarts, which is misleading — the sessions
  themselves do not. The two are coupled at the lifecycle layer.

## How this compares to what I know

**In Scala / Typelevel this would be...**

- **`StripedKeyLock`** is a poor man's keyed `Semaphore[F]`. The Typelevel
  shape would be `Ref[F, Map[String, Semaphore[F]]]` or a `MapRef`, with
  `Semaphore.make(1)` per key. The semantics are the same: same key serializes,
  different keys are independent. Java's `ReentrantLock` is "reentrant by the
  same thread"; `Semaphore[F]` is "reentrant by the same fiber" if you build
  it that way, or strictly non-reentrant if you don't. We use reentrant here
  because the JVM mental model expects it.
- **`Jackson2JsonRedisSerializer<T>`** is a derived `Codec[T]`. The Typelevel
  equivalent is a Circe `Decoder[T]` + `Encoder[T]` (or io.circe.generic semi-
  auto) plus a `RedisCodec[String, T]` adapter. Both projects share the
  "derive the codec from the type, wire it once at the edge" pattern.
- **`RedisTemplate.opsForValue().set(k, v, ttl)`** is `Redis[F].setEx(k, ttl, v)`
  in `redis4cats`. The atomicity guarantee (value and TTL set together in one
  command) is the same on both sides because both libraries call the same
  Redis `SET` with `EX|PX`.
- **`@ConfigurationProperties` with `Duration`** is pureconfig's
  `ConfigSource.default.at("chess.redis").load[RedisProperties]` where
  `RedisProperties(activeStateTtl: FiniteDuration)`. Both ecosystems do the
  same thing: a single source of truth for the typed shape, bound from text
  at startup, fail-fast on malformed input.

**In Node this would be...**

- A wrapper around `ioredis` exposing `findById / save / compute` and a
  pure-JS `Map<string, Promise<void>>` to serialize per-key access (chained
  via `.then` to enforce ordering, since Node has no thread model). The
  TTL mechanic is identical (`SET key value EX 86400`), and the
  `Optional<Piece>` problem does not exist because JSON natively encodes
  `null` and JS does not distinguish "missing" from "null".

## Gotchas / things I learned the hard way

- `Jackson2JsonRedisSerializer<T>` has historically had a constructor that
  takes only a `Class<T>` and creates its own internal `ObjectMapper`. That
  mapper does **not** have the Java 8 modules registered, so an `Optional<T>`
  field will serialize as the raw `Optional` wrapper (with `present` and
  `value` keys) instead of either the inner value or `null`. The fix is the
  newer constructor `Jackson2JsonRedisSerializer(ObjectMapper, Class<T>)`,
  passing the Spring-context `ObjectMapper`. This is the kind of footgun you
  do not see until a `Move` with a promotion round-trips and the assertion
  fails on the deserialized side — covered explicitly by `RedisGameStoreIT`.

- The `RedisTemplate` is *not* an `@Bean` provided by Spring Boot out of the
  box for arbitrary value types. Spring Boot autoconfigures a
  `RedisTemplate<Object, Object>` and a `StringRedisTemplate`; the typed ones
  are on you. Forgetting to call `template.afterPropertiesSet()` after the
  programmatic setup is the other classic bug — the template will fail at
  first use with a NullPointerException deep in the connection layer because
  the default-serializer fallback was not initialised.

- `getExpire(key, MILLISECONDS)` returns a `Long`; the integer-arithmetic
  comparison `assertThat(ttl).isLessThan(Duration.toMillis())` needs the
  argument to be `long`, not `int`. Easy to miss in a fresh test.

## To dig deeper

- [Spring Data Redis reference manual](https://docs.spring.io/spring-data/redis/reference/)
- [Spring Boot — Externalized Configuration](https://docs.spring.io/spring-boot/reference/features/external-config.html)
- [Redis SET command with TTL options](https://redis.io/commands/set/)
- [Why `@RedisHash` does not fit Java records (issue thread)](https://github.com/spring-projects/spring-data-redis/issues/2495)
- [Testcontainers — Redis module](https://java.testcontainers.org/modules/databases/redis/) (we use a `GenericContainer` here, but the page is useful background)

## File map

- `src/main/java/io/github/dariogguillen/chess/cache/RedisRoomStore.java` —
  `RoomStore` implementation backed by `RedisTemplate<String, Room>`; sets TTL
  on every save; per-key local lock around `compute`.
- `src/main/java/io/github/dariogguillen/chess/cache/RedisGameStore.java` —
  same for `Game` on the `game:{id}` keyspace.
- `src/main/java/io/github/dariogguillen/chess/cache/StripedKeyLock.java` —
  `ConcurrentHashMap<String, ReentrantLock>` registry, shared shape between
  the two stores.
- `src/main/java/io/github/dariogguillen/chess/config/RedisConfig.java` —
  two typed `RedisTemplate` beans plus `@EnableConfigurationProperties`.
- `src/main/java/io/github/dariogguillen/chess/config/RedisActiveStateProperties.java` —
  `@ConfigurationProperties("chess.redis")` record with `Duration activeStateTtl`.
- `src/main/resources/application.yml` — `chess.redis.active-state-ttl: 24h`
  with `CHESS_REDIS_ACTIVE_STATE_TTL` env override.
- `docs/architecture.md` — new "Active state in Redis" subsection under
  "State strategy"; updated layered-architecture mention so the Redis stores
  are the registered adapters.
- `src/test/java/io/github/dariogguillen/chess/cache/StripedKeyLockTest.java` —
  unit: same key → same lock, different keys → distinct, reentrant,
  concurrent acquisitions serialize.
- `src/test/java/io/github/dariogguillen/chess/cache/RedisRoomStoreIT.java` —
  IT: save/find/compute round-trips, concurrent compute on the same key
  serializes (exactly one of N joiners wins).
- `src/test/java/io/github/dariogguillen/chess/cache/RedisGameStoreIT.java` —
  IT: same for `Game`, including a move-history list with a `QUEEN`
  promotion that round-trips through the serializer.
- `src/test/java/io/github/dariogguillen/chess/cache/RedisTtlIT.java` — IT
  with `@TestPropertySource(properties = "chess.redis.active-state-ttl=30s")`:
  `save` sets the TTL close to the configured value; a second `save` after a
  wait refreshes it; `compute` refreshes it on a successful write.
