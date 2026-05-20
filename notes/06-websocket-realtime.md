# Feature 06 — WebSocket real-time broadcasts

**Feature ID:** `websocket-realtime` (from `feature_list.json`)

**Status:** in progress

---

## What we built

After every successful `POST /api/games/{id}/moves`, the server broadcasts a
`MoveEvent` over STOMP-over-WebSocket to `/topic/games/{gameId}`. Subscribers
(the opponent's client, future spectators) stop polling and start consuming the
live tail in real time. REST stays the entry point for mutations; STOMP is a
fire-and-forget side channel for read-only push. This is also the first
**cross-repo contract** in the project — the `chess-frontend` repo will mirror
the STOMP shape documented in `docs/architecture.md` → "STOMP API contract"
when it reaches its own feature 5 (`stomp-live-updates`).

## Java / Spring concepts that appear

- **`@EnableWebSocketMessageBroker`** — flips Spring into "STOMP over
  WebSocket" mode. It is a layer on top of the raw `@EnableWebSocket`
  primitive: instead of writing a `WebSocketHandler` and parsing frames by
  hand, you get a STOMP broker, message conversion, destination routing, and
  the `SimpMessagingTemplate` bean for server-side publishes. The
  `WebSocketMessageBrokerConfigurer` interface is the seam where you register
  endpoints and brokers. See [Spring's WebSocket / STOMP
  reference](https://docs.spring.io/spring-framework/reference/web/websocket/stomp.html).

- **`SimpleBroker` vs an external broker.** `registry.enableSimpleBroker("/topic")`
  registers Spring's in-process broker on the `/topic` prefix. It keeps the
  subscription registry and fan-out in JVM memory — no network hop, no extra
  process. The upgrade path is `enableStompBrokerRelay(...)` plus a RabbitMQ
  (or ActiveMQ, or Redis-with-STOMP) host: same destinations, same client
  code, just a different broker behind the curtain. We documented the
  scaling cliff in `docs/architecture.md` so it is a known cost, not a
  hidden one.

- **`SimpMessagingTemplate.convertAndSend(destination, payload)`** — the
  server-side publish API. The payload (`MoveEvent`) is serialized through
  Spring's `MessageConverter` chain (Jackson by default, so records work out
  of the box), wrapped in a STOMP `MESSAGE` frame, and dispatched to every
  subscriber of the destination by the broker. The call is thread-safe; we
  call it from `GameService` on the request thread without ceremony.

- **`@SubscribeMapping` vs broker subscriptions.** Two ways to handle a
  STOMP `SUBSCRIBE` in Spring: a `@SubscribeMapping("/foo")` method runs
  once per subscription and the return value is sent back to the subscriber
  (request/response on top of subscribe); a **broker subscription** is
  fire-and-forget pub/sub — the broker just remembers the session and
  destination, and routes future `convertAndSend` calls. We use the latter.
  `MoveEvent` is not "what the subscriber asked for at subscribe time"; it
  is "what happens later, every time a move lands."

- **`WebSocketStompClient` in tests.** The Spring-provided client used by
  `GameWebSocketIT`. It wraps a `StandardWebSocketClient` (the JSR-356
  client), adds a `MappingJackson2MessageConverter` so the `MoveEvent` JSON
  deserializes to the record, and exposes `connectAsync(...)` returning a
  `StompSession`. The async pattern of "submit the test action, await a
  result on a `BlockingQueue` with a timeout" is the same shape as awaiting
  on a `cats.effect.std.Queue[F, A]` — just blocking on JVM threads instead
  of suspending in an effect.

- **Side effect after atomic mutation.** The broadcast lives **outside** the
  `GameStore.compute` lambda. Inside the lambda is the wrong place: a
  broker-side failure (network blip, serialization quirk) would propagate
  out of `compute` and look like a failed mutation — which it is not, the
  state is already committed. Moving the broadcast outside is the same
  separation we apply when keeping controllers free of try/catch.

## Decisions taken

**Broadcast outside the `compute` lambda, not inside.**

- Decision: `messagingTemplate.convertAndSend(...)` is called after
  `gameStore.compute(...)` returns successfully, in `GameService.applyMove`.
- Alternatives: call it as the last statement of the `compute` lambda
  (atomic with the mutation); call it from the controller after the service
  returns (moves a service-layer concern to `web/`).
- Why: a broker-side `RuntimeException` thrown inside the lambda would bubble
  out of `compute` and reach the controller as if the mutation had failed —
  but it has not, the new `Game` is already in the store. Calling from the
  controller would split the read-check-write-broadcast pipeline across two
  layers and force the controller to know about STOMP. The current shape
  keeps the broadcast in the service (where the broadcast pair to the
  mutation lives) and outside the atomic block (where its failure mode is
  honest). Trade-off: there is a small window where the mutation has
  happened but the broadcast has not yet (or has failed). Subscribers
  tolerate it via reconnect + REST resync.

**SimpleBroker over an external broker.**

- Decision: `registry.enableSimpleBroker("/topic")` — Spring's in-process
  broker.
- Alternatives: RabbitMQ via `enableStompBrokerRelay`; Redis pub/sub with a
  STOMP plugin; ActiveMQ.
- Why: single-instance deployment for the portfolio scope; zero ops
  overhead; same client-side API as the external variants so the upgrade
  path is a config change, not a redesign. The cost — broadcasts do not
  cross instances — is a constraint we accept and document in
  `docs/architecture.md` as a scaling cliff to revisit when (if) we need
  more than one node.

**No SockJS fallback.**

- Decision: `addEndpoint("/ws")` only, no `.withSockJS()`.
- Alternatives: enable SockJS for legacy-browser / proxy compatibility.
- Why: modern browsers handle native WebSocket fine, and the planned
  `@stomp/stompjs` frontend client speaks STOMP over native WS natively.
  SockJS adds a fallback ladder (XHR streaming, long-polling) we do not
  need and a JS shim the client also has to load. Less surface area, fewer
  failure modes, identical behaviour in practice.

**`MoveEvent` flat record, not a nested DTO reused from `web/game/`.**

- Decision: a brand-new `record MoveEvent(...)` in
  `io.github.dariogguillen.chess.websocket`, with `from`/`to`/`promotion`
  as flat fields. No reuse of `GameStateResponse.MoveSummary`.
- Alternatives: nest a `MoveSummary` inside `MoveEvent`; reuse the existing
  `MoveSummary` directly (would couple `websocket/` to `web/game/`).
- Why: the wire shape of `from`/`to`/`promotion` is identical to the REST
  one, but the Java types do not need to be shared. Coupling
  `websocket/` to `web/game/` would mean a refactor in the REST package
  could ripple to the STOMP one. Flat fields keep the wire shape obvious
  at a glance and the two packages independent.

**No STOMP-level authentication.**

- Decision: anyone who can complete the WebSocket handshake from an
  allowed origin can subscribe to any `/topic/games/{gameId}`.
- Alternatives: gate the subscription on a session token derived from the
  player's id; require an auth header on the STOMP `CONNECT` frame.
- Why: matches the REST `GET /api/games/{id}` design (also
  unauthenticated). Feature 6.5 (spectator mode) explicitly relies on this:
  a spectator is any party that subscribed without being a player. When we
  introduce auth, both surfaces gain it together. The mutation surface
  (POST `/moves`) is still gated on `X-Player-Id` matching the side to
  move — a subscriber cannot inject moves, only observe them.

**Broadcast-to-all on the topic, not per-user destinations.**

- Decision: a single `/topic/games/{gameId}` destination per game.
  Subscribers filter their own moves client-side using the `movedBy` field.
- Alternatives: `/user/queue/...` per-user destinations so the originator
  is not echoed; two destinations (one for players, one for spectators).
- Why: broadcast-to-all is simpler — one server-side publish call, the
  broker fans out. Spectators get the same channel for free (feature 6.5).
  The originator's filter is a one-line `event.movedBy === myPlayerId`
  check, well within frontend ergonomics.

**Topic naming `/topic/games/{gameId}`.**

- Decision: mirror the REST path `/api/games/{id}` in the topic shape.
- Alternatives: `/topic/game.{gameId}`, `/topic/g/{gameId}`, single topic
  with a `gameId` field in the payload.
- Why: the symmetry reads. Anyone who knows the REST path can derive the
  topic, and vice versa. A single global topic with payload filtering
  would defeat the broker's per-destination fan-out and force every client
  to receive every game's events. No serious alternative.

## How this compares to what I know

- **`SimpMessagingTemplate.convertAndSend` vs `fs2.concurrent.Topic[F, A]`.**
  In a Typelevel stack, the natural primitive is `Topic[F, MoveEvent]`:
  one publisher (the move service), many subscribers, fire-and-forget
  fan-out, broadcast semantics. Spring's `SimpleBroker` is the same shape
  one layer deeper — instead of `topic.publish1(event)` you call
  `messagingTemplate.convertAndSend(destination, event)`, and instead of
  passing the `Topic` around as a value you reach it via a singleton
  bean. The thread-safety story is the same in both worlds; the
  ergonomics differ in that fs2's `Topic` is a value you compose with
  other streams, while `SimpMessagingTemplate` is a side-effecting
  template you call from a method.

- **vs http4s WebSocket.** http4s exposes `WebSocketBuilder[F]` and you
  build your own framing protocol on top — text frames, JSON, whatever.
  STOMP-over-WS is what you would build if you wrote your own
  subscribe/publish layer with named destinations on top of http4s's
  raw frames. Spring ships that layer; with http4s you compose it from
  fs2 primitives. The trade-off is the usual one: more control vs more
  battery.

- **vs Node `socket.io`.** The `refactor-base` branch of `chess-game`
  used socket.io with `socket.emit('move', payload)` and
  `socket.to(room).emit('move', payload)`. socket.io's "rooms" map
  cleanly to STOMP "destinations" — both are server-managed subscription
  registries with broadcast semantics. The difference is that socket.io
  invents its own protocol (and ships its own JS client), while STOMP is
  a standard with multiple compatible clients (`@stomp/stompjs`,
  `webstomp-client`, Spring's `WebSocketStompClient`, etc.). Switching
  brokers later is easier with STOMP.

## Gotchas / things I learned the hard way

- **`session.subscribe(...)` returns before the broker has registered
  the destination.** In the integration test, calling
  `applyMove(...)` immediately after `subscribe(...)` raced the
  subscription registration and the queue stayed empty.
  A brief `Thread.sleep(200ms)` after subscribe is the standard
  workaround in Spring's own STOMP IT examples. Two seconds is
  overkill; 200ms is enough for a localhost handshake and not
  noticeable across five tests.

- **`new WebSocketStompClient()` without a transport raises at
  connect time.** The no-arg constructor exists but the client needs
  an underlying `WebSocketClient` to actually open a socket — pass a
  `StandardWebSocketClient` (the JSR-356 implementation Spring ships)
  to the constructor.

- **The default `MessageConverter` is "string only" — the test will
  fail to deserialize `MoveEvent` until you set
  `stompClient.setMessageConverter(new MappingJackson2MessageConverter())`.**
  Without it, `handleFrame`'s `payload` arrives as a `String` and the
  cast to `MoveEvent` throws `ClassCastException`. The production
  side already has Jackson on the classpath; the client side has to
  opt in explicitly.

- **`MappingJackson2MessageConverter` ships with its OWN
  `ObjectMapper`, not Spring Boot's autoconfigured one.** That bare
  `ObjectMapper` does not register `JavaTimeModule`, so
  deserializing `MoveEvent.playedAt` (an `Instant`) throws inside
  the `StompFrameHandler` and the queue stays empty forever — the
  test fails with "actual not to be null" and there is no
  exception in the logs (the handler swallows it). The fix:
  construct an `ObjectMapper`, register `JavaTimeModule`, and call
  `converter.setObjectMapper(mapper)`. Took longer than it should
  have to diagnose because the symptom (silent receive timeout) is
  the same as a slow subscription registration.

- **`@SpringBootTest(webEnvironment = RANDOM_PORT)` + `MockMvc` is
  not how to WebSocket-test.** `MockMvc` simulates the dispatcher
  servlet; it does not run a real connector, so there is no port to
  WebSocket-handshake against. The IT here uses `RANDOM_PORT` +
  `@LocalServerPort` and talks to `ws://localhost:{port}/ws`
  directly. The companion REST calls inside the same test use
  `RestTemplate` (also pointed at the random port) because the
  same Spring context is serving both surfaces.

- **`RestTemplate` throws on 4xx by default.** The negative tests
  (`illegalMove_doesNotBroadcast`, `moveByWrongPlayer_doesNotBroadcast`)
  need to assert on the 422 status, not catch an exception. Wrapping
  the call in a try/catch on `HttpStatusCodeException` and
  reconstructing a `ResponseEntity` from the exception keeps the
  test ergonomics consistent across 2xx and 4xx paths. `WebClient`
  would not need this dance; we picked `RestTemplate` to stay in
  the blocking-IT style the existing ITs use.

- **`Side` derivation from `moveNumber` parity.** After the move is
  appended, an odd `moveNumber` means White's move just landed and an
  even `moveNumber` means Black's. The `turn` field is the inverse
  (next mover). The compute lambda inside `applyMove` derives "who
  should be moving now" from `moves.size() % 2 == 0` *before* the
  append; the broadcast code derives "who just moved" from
  `moveNumber % 2 == 1` *after* the append. Same parity coin, opposite
  faces.

## To dig deeper

- [Spring's STOMP-over-WebSocket
  reference](https://docs.spring.io/spring-framework/reference/web/websocket/stomp.html)
  — the canonical write-up; covers the broker choice, message flow,
  destination semantics, and testing patterns.
- [Spring's WebSocket testing
  guide](https://docs.spring.io/spring-framework/reference/web/websocket/stomp/testing.html)
  — where the `WebSocketStompClient` + `BlockingQueue` pattern comes from.
- [The STOMP 1.2
  protocol](https://stomp.github.io/stomp-specification-1.2.html) — short,
  worth reading once to know what frames the broker is exchanging.
- [`@stomp/stompjs`](https://stomp-js.github.io/stomp-websocket/codo/) —
  the client library the `chess-frontend` repo will use when it consumes
  this contract.

## File map

New files:

- `src/main/java/io/github/dariogguillen/chess/config/WebSocketConfig.java` —
  `@EnableWebSocketMessageBroker` configuration. Registers `/ws` with allowed
  origins and enables `SimpleBroker` on `/topic`.
- `src/main/java/io/github/dariogguillen/chess/websocket/MoveEvent.java` —
  the wire shape broadcast after every successful move.
- `src/test/java/io/github/dariogguillen/chess/websocket/GameWebSocketIT.java` —
  5-test IT covering single-subscriber receipt, two-subscriber fan-out,
  cross-game isolation, illegal-move silence, and wrong-player silence.
- `notes/06-websocket-realtime.md` — this file.

Modified files:

- `src/main/java/io/github/dariogguillen/chess/service/GameService.java` —
  injects `SimpMessagingTemplate` and `Clock` via constructor; after
  `gameStore.compute(...)` returns, builds a `MoveEvent` and broadcasts it
  to `/topic/games/{gameId}`. The broadcast is wrapped in a try/catch on
  `RuntimeException` that logs at `WARN` without rethrowing.
- `docs/architecture.md` — added the "STOMP API contract" section
  documenting the endpoint, broker choice, allowed origins, topic shape,
  `MoveEvent` payload, no-auth design choice, failure mode, and the
  cross-repo coordination note.
- `README.md` — added a "WebSocket (STOMP)" subsection under "API" with
  the endpoint URL, the topic pattern, a pointer to
  `docs/architecture.md`, and a `wscat` smoke-test snippet.
