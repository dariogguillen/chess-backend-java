# Feature 23 — Play against a bot (Stockfish via subprocess)

**Feature ID:** `bot-opponent` (from `feature_list.json`)

**Status:** implemented, in review

---

## What we built

A "play against the bot" room-creation path. When the creator picks
`opponentKind=BOT`, the backend immediately builds a complete two-side
game — the human on their chosen side, a fixed "Stockfish" bot on the
other — and, on the bot's turn, computes a move with a Stockfish
subprocess (UCI) and feeds it through the **same** `applyMove` pipeline a
human move uses. The bot's `MoveEvent` is therefore indistinguishable on
the wire from a two-human move. The bot side is just-another-player
behind a sentinel `Player` id; the engine sits behind a port so the test
suite runs green without the binary installed.

## Java / Spring concepts that appear

- **`ProcessBuilder` + the UCI line protocol** — `new
  ProcessBuilder("/usr/games/stockfish").start()` spawns an external
  process; you write commands to `process.getOutputStream()` and read
  replies from `process.getInputStream()`, line by line. UCI is a plain
  newline-delimited text protocol (`uci` → `uciok`, `isready` →
  `readyok`, `position fen <fen>`, `go movetime <ms>` → `bestmove
  e2e4`). The two JVM-subprocess idioms that matter:
  `process.waitFor(timeout, TimeUnit)` (a hard wall-clock deadline that
  returns `false` instead of blocking forever) and **`destroyForcibly()`
  in a `finally`** (guaranteed teardown — a normal exit makes it a no-op,
  a hung process is `SIGKILL`ed). See
  [`Process`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Process.html).
- **Ports & adapters (hexagonal seam).** `BotEngine` is an interface
  (`Move chooseMove(String fen)`); `StockfishBotEngine` is the one
  production `@Component`. Tests swap it with `@MockitoBean BotEngine` and
  script moves — so `./init.sh` never needs Stockfish. Only the gated
  `StockfishEngineIT` touches the real binary.
- **A dedicated `ExecutorService` bean.** `BotConfig` exposes a small
  fixed daemon pool via `Executors.newFixedThreadPool(...)` with a custom
  `ThreadFactory`, and a `@PreDestroy` that `shutdown()`s gracefully then
  `shutdownNow()`s. `BotMoveService` injects it by `@Qualifier`. This is
  off-request fire-and-forget: the human's `POST /moves` returns
  immediately; the bot's move lands later as a normal broadcast.
- **`@ConfigurationProperties` with compact-constructor validation.**
  `BotProperties` (`chess.bot.*`) binds `engine-path`, `move-time`,
  `move-timeout`, `pool-size`, and a record compact constructor rejects a
  blank path / non-positive durations / `move-timeout <= move-time` at
  bean-binding time (fail-fast at boot, the same pattern as
  `DisconnectProperties` / `CorsProperties`).
- **`assumeTrue` for a gated test.** JUnit's
  `Assumptions.assumeTrue(condition, message)` turns a missing
  precondition into a **skip**, not a failure. `StockfishEngineIT` skips
  cleanly when the binary is absent — visible as `Skipped: 1` in the
  failsafe report.
- **Sealed-interface STOMP discriminated union.** `GameEngineFailedEvent`
  joins the `GameStateEvent permits ...` family with `type =
  "GAME_ENGINE_FAILED"`, the same explicit-discriminator pattern features
  9.5 / 11.5 / 22 use.

## Decisions taken

- **Decision:** Stockfish via subprocess, **spawn-per-move**.
  **Alternatives:** a long-lived process per game (or a pool of
  processes) kept warm; a pure-Java minimax engine.
  **Why this one:** the FEN fully describes the position, so a fresh
  process per move is stateless, trivially correct under concurrency, and
  leak-proof on a memory-constrained t3.micro — no per-game process
  bookkeeping, no zombie-reaping across a game's lifetime. Stockfish (vs
  a hand-rolled minimax) is the higher portfolio signal and is actually
  strong. The cost is ~a process spawn per move, invisible next to a
  500ms search.

- **Decision:** the bot identity is a **sentinel `Player.id`**, with
  `userId = null`.
  **Alternatives:** a sentinel on `userId`; a real `users` row for the
  bot; a `boolean isBot` field on `Player`.
  **Why this one:** `userId` is the FK to `users(id)` that the archive
  path writes into `games.{white,black}_user_id`. An invented `userId`
  would violate that FK at archive time. Putting the sentinel on `id`
  (which is *not* an FK) lets the bot archive exactly like a guest
  (`userId = null`) while still being recognisable via `Player.isBot()`.

- **Decision:** engine failure **reuses** `GameStatus.ABANDONED` (not a
  new status), and is distinguished only by a new STOMP event.
  **Alternatives:** a new `GameStatus.ENGINE_FAILED`.
  **Why this one:** a new `GameStatus` value cascades into every
  `@Schema(allowableValues = …)` that enumerates statuses
  (`MyGameSummary`, `PlayerGameSummary`) plus the `isTerminal()` set and
  the frontend's typed union — a lot of surface for what is, outcome-wise,
  "the game ended, the human wins by forfeit". The new
  `GAME_ENGINE_FAILED` event carries the *reason* without touching the
  status enum.

- **Decision:** bot move is **async on a dedicated executor**, reusing
  `GameService.applyMove` verbatim.
  **Alternatives:** compute the bot move synchronously inside the human's
  request; run it on the existing clock `TaskScheduler`; fork a parallel
  move pipeline for the bot.
  **Why this one:** synchronous would block the human's HTTP response for
  the whole search. The clock `TaskScheduler` is a 2-thread pool sized for
  *non-blocking* flag timers; a ~500ms blocking search there would starve
  flag detection. A separate pool isolates the blocking work. Reusing
  `applyMove` (rather than forking) is what makes the bot's move
  wire-identical and keeps validation/clock/archive in exactly one place.

## How this compares to what I know

- **In Scala / Typelevel this would be...**
  - `BotEngine` is a **tagless-final algebra**: `trait BotEngine[F[_]] {
    def chooseMove(fen: String): F[Move] }`, with a `StockfishBotEngine[F:
    Sync]` interpreter and a `TestBotEngine[F]` for specs — the exact
    port/adapter split, but the effect type makes the side effect
    explicit in the signature.
  - The subprocess is a **`Resource[F, Process]`** with a guaranteed
    `release` (`Resource.make(spawn)(p => Sync[F].delay(p.destroyForcibly))`)
    — the cats-effect equivalent of the Java `try/finally + destroyForcibly`.
    The bracket guarantees release on cancellation too, which the `finally`
    approximates.
  - The async move is `F.start` producing a `Fiber` (fire-and-forget), or
    better a `Supervisor[F]` that owns the fiber's lifecycle; the
    dedicated `ExecutorService` is the analogue of running that fiber on a
    **dedicated `ExecutionContext`** (`Blocking`/a bounded pool) so the
    search does not starve the compute pool — the same starvation concern
    as keeping the search off the clock `TaskScheduler`.
  - `GameEngineFailedEvent` joining the sealed family is a plain Scala
    `sealed trait` ADT + a circe encoder `withDiscriminator("type")`.
- **In Node this would be...** `child_process.spawn('stockfish')`, write
  UCI to `child.stdin`, parse `child.stdout` line events, and a
  `setTimeout` + `child.kill('SIGKILL')` race for the deadline (`Promise`
  that rejects on timeout). The "dedicated executor" has no direct analogue
  — Node's single event loop means the subprocess I/O is already async and
  non-blocking, so you don't need a separate thread pool; you just don't
  `await` it inside the request handler. The sentinel-id idea ports
  directly.

## Gotchas / things I learned the hard way

- A **bot-as-white opening move can't be caught by a STOMP
  subscribe-after-create** in a test: the move is triggered at room
  creation and runs async, landing before the test client can subscribe
  (no replay on the topic). The `BotGameIT` asserts the opening via the
  authoritative `GameStore` and pins the wire-identical `MoveEvent` on the
  *reply* path instead.
- Scripting the test double **by FEN** is brittle (you'd have to hard-code
  canonical FENs chesslib emits); scripting **by call order**
  (`thenReturn(f3).thenReturn(g4)`) is FEN-agnostic and robust, since the
  test drives the exact move sequence.
- On Ubuntu Jammy, `stockfish` lives in the **multiverse** repo, so the
  Dockerfile has to `add-apt-repository multiverse` before
  `apt-get install`. The binary installs at `/usr/games/stockfish` (the
  `chess.bot.engine-path` default), not `/usr/bin`.

## To dig deeper

- [UCI protocol specification](https://gist.github.com/DOBRO/2592c6dad754ba67e6dcaec8c90165bf)
  — the canonical command reference (`uci`, `isready`, `position`, `go`,
  `bestmove`).
- [`Process.waitFor(long, TimeUnit)`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Process.html#waitFor(long,java.util.concurrent.TimeUnit))
  and `destroyForcibly()` — the timeout + guaranteed-kill idioms.
- [Spring `@ConfigurationProperties`](https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.typesafe-configuration-properties)
  — relaxed binding + `Duration` parsing.
- [JUnit 5 Assumptions](https://junit.org/junit5/docs/current/user-guide/#writing-tests-assumptions)
  — `assumeTrue` for environment-gated tests.

## File map

- `src/main/java/.../domain/OpponentKind.java` — NEW enum `{ FRIEND, BOT }`.
- `src/main/java/.../domain/Player.java` — bot identity: `BOT_PLAYER_ID`,
  `BOT_DISPLAY_NAME`, `bot()` factory, `isBot()` predicate.
- `src/main/java/.../service/bot/BotEngine.java` — NEW port.
- `src/main/java/.../service/bot/StockfishBotEngine.java` — NEW production
  adapter (subprocess + UCI + guaranteed teardown).
- `src/main/java/.../service/bot/UciMove.java` — NEW UCI-string → `Move`
  parser.
- `src/main/java/.../service/bot/BotEngineException.java` — NEW.
- `src/main/java/.../service/bot/BotMoveService.java` — NEW async
  orchestration + engine-failure terminal path.
- `src/main/java/.../config/BotProperties.java` — NEW `chess.bot.*` binding.
- `src/main/java/.../config/BotConfig.java` — NEW dedicated executor bean.
- `src/main/java/.../service/RoomService.java` — `createRoom` gains
  `OpponentKind`; the BOT branch builds the two-side game; `CreatedRoom`
  carries the game.
- `src/main/java/.../web/room/CreateRoomRequest.java` — nullable
  `opponentKind`.
- `src/main/java/.../web/room/RoomController.java` — thread `opponentKind`,
  return the bot `gameId`, trigger the bot's first move when it is white.
- `src/main/java/.../web/game/GameController.java` — trigger the bot reply
  after a human move.
- `src/main/java/.../websocket/GameEngineFailedEvent.java` — NEW STOMP
  event; added to the `GameStateEvent permits`.
- `src/test/java/.../service/bot/UciMoveTest.java` — UCI parsing unit test.
- `src/test/java/.../service/bot/BotMoveServiceIT.java` — happy path,
  engine-failure terminal path, not-bot-turn / already-terminal no-ops.
- `src/test/java/.../websocket/BotGameIT.java` — end-to-end with the mocked
  engine (immediate gameId, wire-identical reply, forced-mate termination,
  bot-as-white opening).
- `src/test/java/.../service/bot/StockfishEngineIT.java` — GATED real-binary
  test (`assumeTrue` Stockfish on PATH).
- `src/test/java/.../service/RoomServiceTest.java` — BOT-branch unit cases.
- `src/main/resources/application.yml` — `chess.bot.*` section.
- `Dockerfile` — installs the Stockfish binary in the runtime stage.
- `docs/architecture.md` — bot integration, STOMP event, deploy
  implications.
