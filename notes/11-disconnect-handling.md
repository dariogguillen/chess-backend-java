## Feature 11 — Disconnect and reconnect (core)

**Feature ID:** `disconnect-handling` (from `feature_list.json`)

**Status:** in progress

---

## What we built

When a player loses their STOMP session mid-game, the server starts a
60-second grace period (configurable via `chess.disconnect.grace-period`).
If the player resubscribes to the same `/topic/games/{gameId}` with the
same `playerId` native header before the timer fires, the timer is
cancelled and the game continues unchanged. If the timer fires, the
game is mutated to `ABANDONED`, archived to Postgres, and a terminal
`GameAbandonedEvent` is broadcast on the existing
`/topic/games/{gameId}` topic so the opponent's UI can transition to
"game over". Mid-grace UX notifications (per-second countdown on the
opponent's screen) are out of scope here and will land with feature
11.5 (`disconnect-notifications`).

## Java / Spring concepts that appear

- **`@EventListener` on STOMP session events.** Spring publishes
  `SessionSubscribeEvent`, `SessionUnsubscribeEvent`, and
  `SessionDisconnectEvent` from the broker layer as ordinary Spring
  application events. Any `@Component` can subscribe with
  `@EventListener` — same mechanism a domain event listener would use.
  `ViewerCountTracker` already exercises this for spectators; the new
  `PlayerSessionTracker` exercises it for the two players of a game.
  The alternative shape, a `ChannelInterceptor` on the message channel,
  intercepts every frame and is correct when you need to mutate
  headers in flight; for event-after-the-fact bookkeeping, the
  application-event hook is the lighter idiom.
  [Spring WebSocket session events](https://docs.spring.io/spring-framework/reference/web/websocket/stomp/handle-broker-relay.html#websocket-stomp-events).
- **Programmatic `TaskScheduler` with `@EnableScheduling`.** The
  `@EnableScheduling` annotation brings up Spring's scheduling
  infrastructure; the `@Scheduled` annotation is the declarative
  consumer (cron / fixed-delay / fixed-rate). For one-shot,
  cancellable, per-key timers we use the programmatic
  `TaskScheduler.schedule(Runnable, Instant)` API directly — it
  returns a `ScheduledFuture<?>` we hold in a `Map` keyed by
  `(playerId, gameId)` and cancel on reconnect.
  [Spring scheduling reference](https://docs.spring.io/spring-framework/reference/integration/scheduling.html).
- **`ScheduledFuture.cancel(false)` semantics.** `cancel(false)` asks
  the scheduler to drop the task if it has not started yet, and to
  let it complete if it is already running. We pass `false`
  (not `true` / "interrupt in progress") because the abandon-task
  body is short and self-cleaning: once it has crossed the per-key
  lock and removed itself from the active map, letting it complete
  is the correct behaviour; interrupting mid-flight on a Redis or
  Postgres write would leave artifacts the next request would have
  to clean up.
- **`StripedKeyLock` reuse.** The per-key `ReentrantLock` cache
  introduced in feature 8 (for `RedisGameStore.compute` atomicity) is
  borrowed for a different domain here — coordinating
  `startGracePeriod` / `cancelGracePeriod` / fire on the same
  `(playerId, gameId)` key. The lock is process-local; that matches
  the single-instance deploy and the in-memory timer map.
- **`@ConfigurationProperties` with `Duration` binding.** Spring
  Boot's relaxed binder reads `chess.disconnect.grace-period=60s`
  (or `PT60S`, `1m`, `200ms` …) into a `java.time.Duration`
  component of a record. Validation runs in the compact
  constructor — a zero or negative duration fails boot loudly. The
  test path overrides with
  `@TestPropertySource(properties = "chess.disconnect.grace-period=300ms")`
  so the integration tests finish in well under a second.
  Same idiom as `CorsProperties` (feature 10) and
  `RedisActiveStateProperties` (feature 8).
- **`@MockitoSpyBean` on `SimpMessagingTemplate`.** The unit-ish
  abandon service IT uses `@MockitoSpyBean` to wrap the real broker
  bean — calls still go through, but Mockito records them for
  verification. A `@MockitoBean` would be the wrong tool: it
  replaces the bean entirely and other components that depend on
  it would see the mock instead of the real STOMP wiring.
  `reset(...)` in `@BeforeEach` keeps spy state from leaking across
  tests.

## Decisions taken

**Programmatic `TaskScheduler.schedule` over `@Scheduled` cron / fixed-delay.**

- Decision: drive the grace timer with
  `taskScheduler.schedule(task, instant)` and store the returned
  `ScheduledFuture<?>` in an in-memory map keyed by
  `(playerId, gameId)`.
- Alternatives: a `@Scheduled(fixedDelay = ...)` sweep that polls
  the disconnected-players map and abandons anyone past their
  deadline; a `@Scheduled` cron tick per minute that scans
  pending timers.
- Why this one: the polling shapes pay a worst-case latency equal
  to the poll interval (a player who disconnects 1 second after a
  poll waits 59 + 60 = 119 seconds, not 60), and they keep the
  scheduler busy even when nobody is disconnected. One-shot
  programmatic scheduling fires at exactly the right moment and
  consumes no CPU when the active set is empty. The trade-off is
  that we hold `ScheduledFuture` handles in memory — but those
  are bounded by "currently-disconnected players" which is tiny
  at portfolio scale.

**In-memory timer map (no Redis persistence) and the server-restart limitation.**

- Decision: keep the
  `Map<(playerId, gameId), ScheduledFuture<?>>` purely in
  process memory.
- Alternatives: persist timer state to Redis with a TTL, then on
  boot scan and reschedule pending timers.
- Why this one: the persistence path is real engineering — pick a
  serialization format for the timer record, decide what
  "now()" means relative to the persisted `fireAt` after a
  restart, handle the case where the restart took longer than
  the grace period — and the deploy is single-instance with low
  traffic. The worst case today is that a JVM restart with a
  pending timer leaves the game stuck in `ONGOING` until
  something else terminates it (a move, or a fresh disconnect
  that schedules a new timer). Documented in
  `docs/architecture.md`; a future feature can layer the
  persistence on if it ever matters in practice.

**Two archive call sites, no shared helper.**

- Decision: `GameService.applyMove` keeps its own
  `gameHistoryService.archive(updated)` invocation (the
  terminal-by-move path from feature 9); `GameAbandonService.abandon`
  has its own invocation (the terminal-by-timeout path, this
  feature).
- Alternatives: extract a private `archiveIfTerminal(Game)` helper
  somewhere (a static on `GameHistoryService`, or on a new
  `TerminalGameLifecycle` interface).
- Why this one: the two call sites differ in more than the archive
  call. `applyMove` runs the archive **inside** its `compute`
  lambda (so a Postgres failure rolls back the Redis advance);
  `abandon` runs the archive **outside** its `compute` block (no
  race against further moves once `ABANDONED` is committed; the
  shorter critical section is the win). A shared helper would
  either lose this asymmetry or grow a boolean parameter to
  preserve it — both worse than two short, explicit call sites.

**`GameAbandonedEvent` ships without a `type` discriminator field.**

- Decision: keep the topic flat. Subscribers distinguish
  `MoveEvent` from `GameAbandonedEvent` by shape (`from`/`to` vs
  `abandonedBy`/`winnerId`) until feature 11.5 retrofits the
  topic into a sealed-interface family.
- Alternatives: add a `type` field to `GameAbandonedEvent` alone
  (asymmetric with `MoveEvent`, would drift); retrofit
  `MoveEvent` to a sealed interface here (expands the scope of
  this feature into 11.5's refactor).
- Why this one: 11.5 is the right moment to introduce the
  sealed-interface family — it ships
  `PlayerDisconnectedEvent` / `PlayerReconnectedEvent` at the
  same time, which makes the polymorphic-topic-gets-discriminator
  rule (from feature 9.5) clearly applicable. Adding the field
  here would force half the refactor now and the other half in
  11.5; deferring it is the cleaner split. The frontend
  coordination cost is one `if (event.abandonedBy)` check until
  11.5 lands.

**Per-key locking on `startGracePeriod` / `cancelGracePeriod` / fire.**

- Decision: every public entry point on `GracePeriodManager`
  acquires the per-key `ReentrantLock` from `StripedKeyLock`
  before touching the active map; the timer task body acquires
  the same lock before removing itself and invoking the abandon
  path.
- Alternatives: rely on `ConcurrentHashMap`'s built-in atomicity
  (`compute` / `remove`) without a separate lock; accept the
  race.
- Why this one: the race is between a `cancel` that removes the
  entry and the task body that also removes the entry and then
  invokes the abandon path. Without serialization the `cancel`
  call can return successfully while the task body is already
  past the map-remove point, and the abandon path runs on a
  game the caller believes it just rescued. The lock closes that
  window. `ConcurrentHashMap.compute` alone cannot close it
  because the task's "remove + invoke" is two operations from
  the map's point of view.

**Spectator disconnects stay with `ViewerCountTracker`.**

- Decision: the new `PlayerSessionTracker` only registers
  associations for sessions whose `playerId` matches white or
  black of the game; spectators are recognised and ignored.
- Alternatives: a single tracker that handles both.
- Why this one: the two flows have nothing in common past the
  shared session event. `ViewerCountTracker` decrements a count
  and re-broadcasts; `PlayerSessionTracker` starts a timer that
  mutates game state. Merging them would create a multi-headed
  class with two unrelated responsibilities; keeping them
  orthogonal is the cleaner shape. The two trackers do
  share-by-coincidence the same `playerId`-header convention,
  the same tolerant UUID parsing, and the same regex for the
  game-topic destination — copy-paste rather than abstraction is
  the right call at this scale.

**Grace period as a `@ConfigurationProperties` knob, not a constant.**

- Decision: bind `chess.disconnect.grace-period` to a
  `DisconnectProperties` record; consumers inject it.
- Alternatives: a `static final Duration` in `GracePeriodManager`.
- Why this one: tests need a much shorter grace period (300 ms
  in `DisconnectHandlingIT`) so the suite does not hang for a
  full minute per scenario; a `@TestPropertySource` override is
  the standard Spring idiom for that. A `static final` would
  force every test to live with the production value or use
  reflection to mutate it. Matches the existing
  `chess.redis.active-state-ttl` and `chess.cors.*` patterns.

## How this compares to what I know

- **In Scala / Typelevel this would be...** an fs2 `Stream` of
  session events fed into a `Map`-keyed `MapRef[F, K, Fiber[F, ...]]`.
  `startGracePeriod` = `Timer[F].sleep(d) >> abandon` spawned as
  a fiber, the fiber handle stored in the `MapRef`.
  `cancelGracePeriod` = `mapRef.modify` to extract the fiber and
  `fiber.cancel`. The per-key lock is `Semaphore[F]` keyed via the
  same `MapRef` shape. The race between `cancel` and the fiber
  finishing is solved the same way Scala solves it: the fiber's
  own `finalize` removes itself from the `MapRef` inside an
  `Resource`'s release path, and `cancel` and `finalize` are
  serialized by the underlying `Ref`'s `modify`. The `cancel(false)`
  argument we pass to `ScheduledFuture.cancel` is Cats Effect's
  default `cancelable` semantics — no thread interruption, just
  "stop the work that has not started yet, let in-flight work
  finish."
- **In Node this would be...** `setTimeout(fn, ms)` returning a
  `Timeout` handle stored in a `Map<string, Timeout>`, with
  `clearTimeout(handle)` as the cancel path. The Node event loop
  is single-threaded, so the per-key lock has no analogue —
  there is no concurrent observer of the map to race against.
  The Java side picks up the cost of explicit locking precisely
  because the scheduler runs on its own worker pool, and the
  reconnect path runs on the STOMP broker's executor.
- `@EventListener(SessionDisconnectEvent.class)` ≈ subscribing
  to an fs2 `Topic[F, SessionEvent]` emitted by the WebSocket
  layer.

## Gotchas / things I learned the hard way

- The race I almost missed: `cancelGracePeriod` and the task body
  both want to `active.remove(key)`. Without the per-key lock, a
  cancel that arrives during the firing window can see the map
  entry already gone (the task body removed it) and assume the
  cancel is a no-op — but the task body is already past that
  point and about to call `abandon`. The frontend sees the game
  end despite a successful reconnect. The per-key lock closes
  the window. Documented inline in `GracePeriodManager`.
- Migrated to `@MockitoSpyBean` (Spring Boot 3.4+ replacement
  for the deprecated `@SpyBean`) in the same round; no other
  `@SpyBean` usages remained in the codebase.
- The IT initially polled with a tight `Thread.sleep` after
  disconnect; Awaitility plus a slightly wider budget (grace +
  2 seconds) is more resilient on CI runners with cold caches.

## To dig deeper

- [Spring scheduling reference](https://docs.spring.io/spring-framework/reference/integration/scheduling.html) — when to use `@Scheduled` vs `TaskScheduler`.
- [Spring WebSocket session events](https://docs.spring.io/spring-framework/reference/web/websocket/stomp/handle-broker-relay.html#websocket-stomp-events) — `SessionSubscribeEvent` / `SessionDisconnectEvent` payloads.
- [Configuration properties: relaxed binding](https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.typesafe-configuration-properties.relaxed-binding) — how `chess.disconnect.grace-period=60s` maps to a `Duration`.
- [`ScheduledFuture.cancel` JavaDoc](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/Future.html#cancel(boolean)) — `mayInterruptIfRunning` semantics.

## File map

- `src/main/java/.../config/DisconnectProperties.java` — typed binding for `chess.disconnect.grace-period`.
- `src/main/java/.../config/SchedulingConfig.java` — `@EnableScheduling`, `TaskScheduler` bean, and enables `DisconnectProperties`.
- `src/main/java/.../service/GracePeriodManager.java` — in-memory per-key timer registry with start/cancel/fire and per-key locking.
- `src/main/java/.../service/GameAbandonService.java` — flips a game to `ABANDONED`, archives it, broadcasts the terminal event.
- `src/main/java/.../websocket/PlayerSessionTracker.java` — STOMP session-event listener that wires session lifecycle to `GracePeriodManager`.
- `src/main/java/.../websocket/GameAbandonedEvent.java` — broadcast payload (no `type` discriminator; feature 11.5 will retrofit).
- `src/main/java/.../domain/Game.java` — added `withStatus(GameStatus)` helper.
- `src/main/resources/application.yml` — exposes `chess.disconnect.grace-period` (default `60s`, env override).
- `docs/architecture.md` — new "Disconnect handling" subsection under "Reconnection".
- `src/test/java/.../service/GracePeriodManagerTest.java` — unit tests for the timer lifecycle.
- `src/test/java/.../service/GameAbandonServiceIT.java` — IT for the abandon service paths.
- `src/test/java/.../websocket/DisconnectHandlingIT.java` — end-to-end STOMP coverage with a 300ms test grace period.
