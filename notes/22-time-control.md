# Feature 22 — Server-authoritative clock (time control + auto-flagging)

**Feature ID:** `time-control` (from `feature_list.json`)

**Status:** implemented, in review

---

## What we built

An **optional** per-player chess clock on the active game. The backend is
the canonical clock source: it decrements the moving player's remaining
time on every move (with a Fischer increment added back), and a per-game
scheduled timer **auto-flags** the game (terminates it with status
`TIMEOUT`) when the side-to-move runs out — even if that player is
offline. Clock state rides on every `MoveEvent`, on `GameStateResponse`,
and on a new terminal `GAME_TIMED_OUT` STOMP event. A room created
without a `timeControl` produces an untimed game whose behaviour is
byte-for-byte the pre-feature-22 one.

## Java / Spring concepts that appear

- **Programmatic `TaskScheduler` per-entity timers vs `@Scheduled`
  polling.** Flag detection is a one-shot `taskScheduler.schedule(Runnable,
  Instant)` per game, held as a `ScheduledFuture` in a
  `ConcurrentHashMap` and cancelled/rescheduled on every move
  (`ClockTimerManager`, mirroring feature 11's `GracePeriodManager`). A
  declarative `@Scheduled` tick cannot do this — its tasks are recurring
  and not addressable for cancellation, and a 1s poll would be both
  imprecise (up to a second late) and wasteful (a Redis keyspace scan
  every tick). The shared `TaskScheduler` bean lives in `SchedulingConfig`.
  [Spring TaskScheduler reference](https://docs.spring.io/spring-framework/reference/integration/scheduling.html).
- **Backwards-compatible record evolution with a *meaningful* null.** The
  `Game` record gains four clock fields, but unlike feature 21's
  `Room.creatorSide` (where a `null` is *defaulted* to `WHITE`), here
  `null` is a legitimate domain state ("untimed"). The compact
  constructor therefore does NOT default — it permits `null` and enforces
  an **all-or-nothing invariant** (all four clock fields null, or all
  four non-null). An 8-arg convenience constructor delegates with the
  four fields null so every existing `new Game(...)` call site and
  Jackson's deserialisation of pre-deploy Redis games stay green (Jackson
  fills a missing record component with `null` on the canonical
  constructor). Same trick as `Player.userId` / `Room.creatorSide`.
- **Idempotent terminal transition through `GameStore.compute`.**
  `GameTimeoutService.timeout` flips the status inside the per-game
  atomic `compute` block, short-circuiting if the game is already
  terminal — the exact `AtomicBoolean transitioned` + `isTerminal()`
  pattern `GameAbandonService` uses. Two independent terminal paths
  (grace → `ABANDONED`, clock → `TIMEOUT`) converge safely on this guard.
- **Records as additive DTOs.** `MoveEvent` and `GameStateResponse` gain
  nullable `Long` clock fields via new convenience constructors; the
  discriminator-bearing `GameStateEvent` sealed interface gains
  `GameTimedOutEvent` in its `permits` clause. Springdoc reflects the new
  `@Schema`-annotated `TimeControl` record components into the OpenAPI
  spec automatically.

## Decisions taken

- **Decision:** per-game scheduled timer for flag detection.
  **Alternatives:** a `@Scheduled(fixedRate=1s)` poll scanning active
  games. **Why this one:** millisecond precision, no keyspace scan, and
  it coexists cleanly with feature 11's grace timer through the shared
  idempotency guard. It is the literal twin of the feature-11 machinery,
  so the codebase gains no new scheduling vocabulary.
- **Decision:** store `incrementMs` on the `Game` (a fourth clock field)
  rather than only the three acceptance-named state fields. **Alternatives:**
  look the `Room.timeControl` up inside `applyMove`. **Why this one:**
  `GameService` has no `RoomStore` dependency, and a cross-aggregate read
  on every move (a Redis keyspace scan via `findByRoomId`) to recover a
  static config value is wasteful. Lifting the increment onto the game
  keeps `applyMove` a pure per-game operation; the all-or-nothing
  invariant naturally extends to the fourth field.
- **Decision:** the clock runs during disconnect, never pauses.
  **Alternatives:** pause the clock while a player is offline.
  **Why this one:** the server cannot distinguish an intentional
  disconnect from an accidental one (same STOMP signal), so pausing would
  be the exact cheat a server-authoritative clock exists to prevent. The
  60s grace period is the only forgiveness; grace and clock race and the
  first to fire wins.
- **Decision:** `TIMEOUT` is a `GameStatus`, not an `ErrorResponse` code.
  **Why this one:** a flag is a terminal game *outcome*, observed via
  state reads and the STOMP event, not a rejected request.

## How this compares to what I know

- **In Scala / Typelevel this would be...** a cancellable `IO`/`Fiber`
  per game: `clockTimer = (Temporal[F].sleep(remaining) *> flag(gameId)).start`,
  cancelled and re-`start`ed on each move — `ClockTimerManager`'s
  `ScheduledFuture` + `cancel()` is the imperative shadow of `Fiber` +
  `Fiber.cancel`. The grace-vs-clock race is literally
  `race(graceTimer, clockTimer)`: the first effect to complete decides
  the terminal status, the loser is cancelled (here: observed as a no-op
  via the `isTerminal()` guard rather than truly cancelled). The
  all-or-nothing clock would be a refined ADT —
  `sealed trait Clock; case object Untimed; case class Timed(whiteMs, blackMs, lastMoveAt, incMs)` —
  which makes the half-clock state *unrepresentable* at compile time.
  Java/Jackson push us toward four nullable fields with a runtime
  invariant instead, chosen because a flat record round-trips through
  Redis JSON without a custom (de)serializer for the sum type. The
  invariant check in the compact constructor is the runtime price we pay
  for the encoding the platform makes ergonomic.
- **In Node this would be...** `setTimeout(() => flagGame(id), remainingMs)`
  storing the `Timeout` handle in a `Map`, `clearTimeout` on each move.
  Same shape; the JVM version differs in that the `TaskScheduler` is a
  bounded daemon thread pool (`SchedulingConfig`, size 2) rather than the
  single-threaded event loop, so the fire bodies acquire a per-game lock
  (`StripedKeyLock`) to avoid racing a concurrent cancel.

## Gotchas / things I learned the hard way

- The end-to-end IT's tiny `initialMs` must stay **above** the
  subscribe-registration delay. White's flag is armed at *join* time, so
  with a 300ms clock the flag fired before the test's "black" subscriber
  finished registering on the topic and the `GAME_TIMED_OUT` broadcast
  landed with nobody listening. Bumped to 2000ms (subscribe delay 1000ms)
  so the subscriber is in place before the flag fires — the
  `DisconnectHandlingIT` idiom subscribes *before* triggering the timer,
  which I had to replicate.
- The increment can't be derived from the remaining-times alone, so it
  has to be carried somewhere `applyMove` can reach without a Room
  lookup. That forced the fourth `Game` field — a small deviation from a
  literal "three nullable fields" reading of the plan, but the only way
  to satisfy the plan's own "add `incrementMs`" clock semantics.

## To dig deeper

- Spring `TaskScheduler` / `ThreadPoolTaskScheduler` —
  https://docs.spring.io/spring-framework/reference/integration/scheduling.html
- Jackson record deserialization & missing-property → null on the
  canonical constructor —
  https://github.com/FasterXML/jackson-databind/issues/2980
- Fischer increment / sudden-death time control —
  https://en.wikipedia.org/wiki/Time_control
- Feature 11's grace-period machinery (`notes/11-disconnect-handling.md`)
  — the pattern this feature mirrors.

## File map

- `src/main/java/.../domain/TimeControl.java` — NEW value object
  `{ initialMs, incrementMs }` with validation + `@Schema`.
- `src/main/java/.../domain/Game.java` — four nullable clock fields,
  all-or-nothing invariant, 8-arg convenience constructor, `isTimed()`,
  `withStatus`/`withClock` carry-through.
- `src/main/java/.../domain/GameStatus.java` — `TIMEOUT` added to the
  enum and `isTerminal()`.
- `src/main/java/.../domain/Room.java` — nullable `TimeControl`; 5-arg
  canonical + 3/4-arg convenience constructors.
- `src/main/java/.../service/ClockTimerManager.java` — NEW per-game flag
  timer registry over the shared `TaskScheduler`.
- `src/main/java/.../service/GameTimeoutService.java` — NEW idempotent
  terminal flip to `TIMEOUT` + archive + `GameTimedOutEvent` broadcast.
- `src/main/java/.../service/RoomService.java` — `createRoom` accepts the
  `TimeControl`; `joinRoom` initialises the clock and arms white's flag.
- `src/main/java/.../service/GameService.java` — `applyMove` clock
  decrement/clamp/increment, MoveEvent clock fields, flag reschedule/cancel.
- `src/main/java/.../websocket/GameTimedOutEvent.java` — NEW STOMP event
  (`type = "GAME_TIMED_OUT"`), added to the `GameStateEvent` permits.
- `src/main/java/.../websocket/MoveEvent.java` — nullable clock fields.
- `src/main/java/.../web/game/GameStateResponse.java` +
  `GameController.java` — nullable clock fields mapped at the boundary.
- `src/main/java/.../web/room/CreateRoomRequest.java` — nullable
  `TimeControl`.
- `src/test/java/.../domain/TimeControlTest.java`,
  `GameStatusTest.java`, `GameTest.java`, `RoomTest.java` — domain units.
- `src/test/java/.../service/GameServiceClockTest.java` — fixed-`Clock`
  decrement/clamp/increment arithmetic.
- `src/test/java/.../service/RoomServiceTest.java` — clock-init +
  first-flag scheduling, timed vs untimed.
- `src/test/java/.../web/game/GameControllerIT.java` — timed + untimed
  REST regression.
- `src/test/java/.../websocket/GameWebSocketIT.java` — MoveEvent clock
  fields (timed + untimed).
- `src/test/java/.../websocket/TimeControlIT.java` — NEW end-to-end
  auto-flag (TIMEOUT + archive + `GAME_TIMED_OUT` broadcast + idempotency).
- `docs/architecture.md` — clock model, no-polling flag detection,
  grace/clock coexistence, API contract + STOMP event.
