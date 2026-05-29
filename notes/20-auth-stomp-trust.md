# Feature 20 — WebSocket trust model (`auth-stomp-trust`)

**Feature ID:** `auth-stomp-trust` (from `feature_list.json`)

**Status:** in progress (closes the auth bundle 16-20)

---

## What we built

The fifth and final feature of the auth bundle (16-20). Adds a single
STOMP `ChannelInterceptor` — `StompAuthInterceptor` in the
`websocket` package — that does two things in two phases:

- **Phase 1 (CONNECT):** if the STOMP CONNECT frame carries an
  `Authorization: Bearer <jwt>` native header and the JWT verifies
  through the same `JwtVerifier` bean the REST filter chain uses,
  the session is identified — a `StompPrincipal` is attached via
  `StompHeaderAccessor.setUser(...)` and the authenticated user
  id is stored on `SimpAttributes`. A missing, malformed,
  expired, or wrongly-signed JWT leaves the session anonymous;
  the CONNECT itself is **never rejected**.
- **Phase 2 (SEND / SUBSCRIBE):** when a frame carries a
  `playerId` native header, the interceptor verifies the claim
  against the session. On a game-scoped destination
  (`/topic/games/{gameId}` or `/app/games/{gameId}/...`), an
  authenticated session can only claim a player whose
  `Player.userId` matches its user id; on a generic destination,
  the pin-on-first-use policy locks the session to whichever
  `playerId` it first sent. Mismatches produce a STOMP ERROR
  frame on the outbound channel and drop the message — the
  session stays open so a buggy client can recover.

This closes the gap the REST `JwtAuthenticationFilter` (feature 16)
already pinned: REST is auth-aware, but until now the WebSocket
surface trusted whichever `playerId` arrived in the header. After
feature 20, an authenticated session cannot impersonate another
user's player, and an anonymous session cannot switch player
identity mid-stream.

## Java / Spring concepts that appear

- **`ChannelInterceptor` on `clientInboundChannel`.** Spring's
  STOMP-over-WebSocket pipeline runs every inbound frame through
  a configurable interceptor chain before any
  `@MessageMapping` handler or broker dispatch sees it. The hook
  is `preSend(Message<?>, MessageChannel)`: return the original
  message to pass through, return a modified message to forward
  a transformed version, or return `null` to **drop** the
  message silently. This feature uses all three return paths.
  Registered via the `configureClientInboundChannel(ChannelRegistration)`
  callback in `WebSocketConfig`. See [Spring reference — Interception](https://docs.spring.io/spring-framework/reference/web/websocket/stomp/interceptors.html).
- **`StompHeaderAccessor` and the native-header layer.** A STOMP
  frame in Spring is a `Message<?>` whose headers include both
  the framework-level keys (command, session id, destination)
  and the per-frame native headers the client actually sent. The
  `Authorization` header from the WebSocket handshake survives
  as a **native** header on the CONNECT frame, accessible via
  `accessor.getNativeHeader("Authorization")` — which returns a
  `List<String>` because STOMP allows repeated headers. Same
  pattern for the `playerId` claim on SEND / SUBSCRIBE.
- **`SimpAttributes` vs `WebSocketSession` attributes.** STOMP
  sessions have a per-session attribute map exposed through
  `StompHeaderAccessor.getSessionAttributes()`. It's a thin
  abstraction over the underlying WebSocket session; storing the
  authenticated user id here lets downstream handlers and later
  interceptor invocations read it without having to re-verify
  the JWT or carry it as a `Principal`. The shape mirrors a
  servlet `HttpSession`, but the lifetime is bound to the
  WebSocket transport, not an HTTP cookie.
- **`StompHeaderAccessor.setUser(Principal)` is the canonical
  identity attachment.** Once set, Spring's `@MessageMapping`
  argument resolver makes the principal available via a
  `Principal` parameter, and downstream interceptors can read it
  via `getUser()`. The principal is per-session, not per-frame,
  so attaching it on CONNECT propagates to every subsequent
  frame on the same session.
- **Outbound ERROR frames via `clientOutboundChannel`.** A STOMP
  ERROR frame from the server is delivered through the same
  `MessageChannel` infrastructure that carries `MoveEvent`
  broadcasts: build a `Message<byte[]>` whose
  `StompHeaderAccessor` is set to `StompCommand.ERROR`, the
  target session id, and a human-readable message, and call
  `clientOutboundChannel.send(...)`. The framework routes it to
  the right transport. Sending it on the **inbound** channel
  (the parameter `preSend` receives) doesn't work — that channel
  is client→broker and the ERROR has nowhere to go from there.
- **`@Lazy` to break a bean-graph cycle.** Spring's
  `@EnableWebSocketMessageBroker` registers
  `clientOutboundChannel` as a bean inside the same
  `WebSocketConfig` that registers this interceptor. An eager
  constructor injection would close the cycle
  `WebSocketConfig → StompAuthInterceptor → clientOutboundChannel →
  WebSocketConfig` and the context would refuse to come up.
  `@Lazy` defers resolution to first use: the bean reference is
  a JDK proxy until something actually calls `.send(...)` on it,
  by which time the broker initialization has completed. Same
  trick is used implicitly when you inject a `SimpMessagingTemplate`
  in some scaffolding setups; here it's explicit.
- **`MessageChannel` vs `SubscribableChannel`.** STOMP's two
  channels (`clientInboundChannel`, `clientOutboundChannel`) are
  `AbstractSubscribableChannel`s — they fan a message out to
  every subscribed handler. The interceptor chain hooks into the
  send side, before any subscriber is notified. The distinction
  matters: `MessageChannel.send` is fire-and-forget; the result
  is `true` on dispatch, NOT on consumption.
- **STOMP ERROR is not the same as a transport disconnect.** The
  ERROR frame is a STOMP-protocol-level signal; the underlying
  WebSocket connection stays open after it. A frontend
  observing an ERROR via the `@stomp/stompjs` client surfaces it
  as a `frameError` callback without closing the socket.
  Compare to a 4xx HTTP response: the request is rejected, the
  session is not. This shape is why this feature uses
  ERROR-then-stay-connected rather than close-the-session — the
  feature-11 grace-period layer would misread a close as an
  unintentional drop.
- **`@MessageMapping` controllers (absent).** There are no
  client-to-server STOMP message handlers in this codebase
  today; the only client-to-server traffic is CONNECT,
  SUBSCRIBE, UNSUBSCRIBE, and the (currently unused) SEND. The
  interceptor's SEND path is forward-looking: it pins the
  contract for the day a `@MessageMapping` handler lands without
  requiring a second round of identity wiring.

## Decisions taken

**Decision: bad JWT → anonymous, NOT rejection.**

- *What:* a missing, malformed, expired, or differently-signed
  JWT on the CONNECT frame all collapse to the same outcome —
  the session connects, the user remains anonymous. The
  CONNECT itself is never refused.
- *Alternatives considered:* the "JWT or 401" pattern the REST
  filter chain uses. Rejected because it would break guest play
  on the WebSocket surface, which contradicts bundle decision 7
  ("anonymous STOMP keeps working").
- *Why this one:* a real frontend rolling out the
  `Authorization`-on-CONNECT change can't migrate atomically
  with the backend. On first-deploy day, every guest connection
  arrives without the header; on the day after, an older
  frontend instance with a now-stale token would still be
  reaching the new backend. Either case must continue working.
  The trade-off is that bad JWTs are silently downgraded to
  anonymous — that's the price of preserving guest play, and
  the log line on the DEBUG level documents the choice for an
  operator who wants to know.

**Decision: identity-spoof → STOMP ERROR frame, NOT disconnect.**

- *What:* a SEND/SUBSCRIBE whose `playerId` claim fails the
  spoof check produces an ERROR frame back to the client and
  drops the message; the session stays connected.
- *Alternatives considered:* immediate disconnect on spoof. The
  symmetric "any bad input closes the transport" model.
  Rejected because the disconnect-handling layer (feature 11)
  treats a transport drop as the trigger for the grace-period
  timer; a spoof would inadvertently fire that timer on the
  opponent's side, exactly the wrong UX. A second alternative —
  silently drop the frame with no ERROR — was rejected because
  a frontend bug (sending the wrong `playerId` by accident)
  would never surface; the ERROR frame is the diagnostic the
  client needs.
- *Why this one:* ERROR-but-no-disconnect is the STOMP-shaped
  equivalent of HTTP's "this request is rejected, the connection
  is fine". A buggy client recovers; the grace-period layer is
  unaffected; the operator-side evidence still exists in the
  interceptor's DEBUG log.

**Decision: `GameStore`, not `GameService`, for game lookup.**

- *What:* the authenticated-session check looks up the game via
  the low-level `GameStore.findById` seam, not via
  `GameService.findById`. The interceptor's constructor takes
  `GameStore`.
- *Alternatives considered:* inject `GameService` (which is what
  `PlayerSessionTracker` does). Rejected because `GameService`
  depends on `SimpMessagingTemplate`, which is published by the
  same `WebSocketConfig` that registers this interceptor —
  closing a bean-graph cycle that the context refuses to
  bootstrap.
- *Why this one:* `GameStore` is the store seam and has no
  STOMP-side dependencies. The interceptor only needs the
  `Optional<Game> findById(UUID)` operation; reaching through
  `GameService` would buy zero behaviour and a hard-to-debug
  context-load failure on bring-up.

**Decision: pin-on-first-use for the anonymous spoof check.**

- *What:* on an anonymous session (or an authenticated session
  whose destination is not game-scoped), the first
  `playerId`-bearing frame pins that id on the session under the
  `pinnedPlayerId` attribute; subsequent frames whose claim does
  not match the pin are rejected.
- *Alternatives considered:*
  - **Require a pre-existing X-Player-Id session attribute** as
    the anchor. This is what the plan's prose suggested, but no
    code path today attaches X-Player-Id to a STOMP session —
    the X-Player-Id flow is REST-only. Wiring a JOIN-handshake
    propagation would have meant touching `RoomService`, the
    WebSocket handshake, and feature 11.7's surface, all
    out-of-scope for feature 20.
  - **Trust every claim** (no anonymous check at all).
    Rejected because the plan's case 5 explicitly asserts that
    an anonymous session must not be able to switch player
    identity mid-stream.
- *Why this one:* pin-on-first-use is the minimum-state
  enforcement that delivers the assertion in case 5 without
  touching any other surface. The pin is per-session and
  short-lived (dies with the session); it is not a global
  identity store.

**Decision: register the interceptor as the only one on
`clientInboundChannel`, and let the existing trackers stay as
`@EventListener`s.**

- *What:* `WebSocketConfig.configureClientInboundChannel(...)`
  registers `StompAuthInterceptor` and nothing else. The
  existing `PlayerSessionTracker` and `ViewerCountTracker` keep
  consuming Spring's session-event bus (`SessionSubscribeEvent`,
  `SessionDisconnectEvent`, etc.).
- *Alternatives considered:* refactor the trackers to be
  `ChannelInterceptor`s themselves so the interceptor chain
  carries all three. Rejected because the existing tests pin
  the event-bus shape and refactoring them is feature-20-scope
  creep.
- *Why this one:* the two mechanisms compose cleanly. The
  interceptor runs first (it's on the inbound channel),
  attaches identity, and then the framework fires the
  `SessionSubscribeEvent` — by the time the tracker reads the
  session attributes, the identity is already in place. The
  ordering invariant holds without the trackers having to know
  about the interceptor.

## How this compares to what I know

- **In Scala / Typelevel this would be...** an http4s server
  with `WebSocketBuilder` + a custom `WebSocketFrame` decoder
  that wraps the inbound stream in a middleware. The middleware
  inspects the first frame (a hand-rolled CONNECT or a
  framework's stomp module), verifies the bearer token via
  `tsec-jwt-mac.verifyAndParse`, and either folds the
  resulting `User` into a `Stream[F, AuthenticatedFrame]` or
  passes through an `AnonymousFrame`. The fs2 pipe shape would
  be roughly:

  ```scala
  def authPipe[F[_]: Async](verifier: JWTMacCV[F, HMACSHA256])
      : Pipe[F, ClientFrame, AuthenticatedClientFrame] = inbound =>
    inbound.evalMap {
      case Connect(headers, _) =>
        headers.get("Authorization") match {
          case Some(bearer) =>
            verifier.verifyAndParse(stripBearer(bearer))
              .attempt
              .map(_.toOption.fold[AuthState](Anonymous)(c => Authenticated(c.subject)))
              .map(state => AuthenticatedConnect(state))
          case None => Async[F].pure(AuthenticatedConnect(Anonymous))
        }
      case other => Async[F].pure(AuthenticatedPassThrough(other))
    }
  ```

  The Spring `ChannelInterceptor` is the same idea imperatively
  — it's a per-frame transformation that can pass through, drop
  (return `null`), or rewrite. The structural difference is
  that http4s threads `AuthState` through the type system (the
  next pipe stage sees `AuthenticatedFrame` and the compiler
  enforces the access shape), while Spring threads it through
  the session attributes (`Map<String, Object>`) and the typed
  guarantee is convention-only. Per-session state lives in
  Spring's `SimpAttributes` map; in Cats Effect it would live
  in a `Resource[F, Ref[F, SessionState]]` constructed at
  CONNECT and released on DISCONNECT — the lifetime is
  explicit in the type.

  Pin-on-first-use is just a `Ref[F, Option[PlayerId]]` with a
  `tryUpdate` that fails on mismatch. The "ERROR-but-no-
  disconnect" pattern maps to emitting an
  `AuthenticationError(reason)` element on the outbound
  `Stream` and continuing — fs2 streams naturally separate
  control-plane events from data-plane closure.

- **In Node this would be...** a Socket.IO server with a
  middleware on the `connection` event that inspects the
  handshake's `auth` payload. Roughly:

  ```js
  io.use(async (socket, next) => {
    const token = socket.handshake.auth?.token;
    if (token) {
      try {
        const { sub } = jwt.verify(token, process.env.AUTH_JWT_SECRET);
        const user = await userRepo.findById(sub);
        if (user) socket.data.user = user;
      } catch (_) { /* anonymous */ }
    }
    next();
  });

  io.on('connection', (socket) => {
    socket.use(([event, ...args], next) => {
      const claimedPlayerId = args[0]?.playerId;
      if (!claimedPlayerId) return next();
      if (socket.data.user && socket.data.user.id !== claimedPlayerId) {
        return next(new Error('spoof'));
      }
      // pin-on-first-use, anonymous case
      if (!socket.data.pinnedPlayerId) {
        socket.data.pinnedPlayerId = claimedPlayerId;
      } else if (socket.data.pinnedPlayerId !== claimedPlayerId) {
        return next(new Error('spoof'));
      }
      next();
    });
  });
  ```

  The Node parallel exposes the architectural difference: in
  Socket.IO, identity is a property of the `socket` object (a
  long-lived JavaScript instance), and you mutate
  `socket.data` directly. In Spring, identity is a property of
  the STOMP session's attribute map, which is one indirection
  away from the underlying WebSocket session — the attribute
  map survives the per-frame interceptor invocation but lives
  in a different scope from a JVM instance. The mechanics
  converge but the abstraction layers don't quite line up.

## Gotchas / things I learned the hard way

- **`@EnableWebSocketMessageBroker` publishes
  `clientOutboundChannel` as a bean, but eagerly resolving it
  from a `@Component` registered in the same `WebSocketConfig`
  closes a graph cycle.** The error message —
  `BeanCurrentlyInCreationException` on
  `DelegatingWebSocketMessageBrokerConfiguration` — was the
  hint. `@Lazy` on the constructor parameter resolves it: the
  reference is a proxy until first use, by which time the
  broker has finished bootstrapping. Same cycle exists for
  `SimpMessagingTemplate`, which is why I switched from
  `GameService` to `GameStore` for the spoof-check lookup —
  `GameService` depends on `SimpMessagingTemplate`.
- **`channel.send(errorMessage)` inside `preSend` does NOT
  deliver a STOMP ERROR to the client if `channel` is the
  inbound channel.** The `MessageChannel` parameter `preSend`
  receives is `clientInboundChannel` (client→broker). ERROR
  frames need to travel server→client, which is
  `clientOutboundChannel`. The wrong choice silently dropped
  the ERROR frame and the test couldn't observe the rejection.
  Inject `clientOutboundChannel` via `@Qualifier`-and-`@Lazy`.
- **`StompSessionHandlerAdapter#handleFrame` is the right hook
  for an ERROR frame on the test client side.** Spring's
  default handler dispatcher routes ERROR frames to
  `handleFrame` when no specific subscription claims them.
  `handleException` is for exceptions raised inside the client
  during frame parsing; `handleTransportError` is for the
  underlying WebSocket transport failing. ERROR-as-protocol
  goes to `handleFrame` with the headers describing the
  rejection.
- **`POST /api/rooms` returns 201, not 200.** The test helper
  initially asserted `HttpStatus.OK`; the actual response is
  `HttpStatus.CREATED`. Quick fix: assert
  `is2xxSuccessful()` instead, since the body shape is what
  matters to the rest of the helper.
- **`StompHeaderAccessor.setUser(...)` is per-session, not
  per-frame.** Setting it on the CONNECT-frame accessor
  propagates because the accessor mutates the underlying
  `MessageHeaders` map; the broker stores the principal on the
  session, and subsequent frames on the same session see it via
  `getUser()`. The semantics surprised me initially because the
  setter looks like a per-frame property — it isn't.
- **`@ChannelInterceptor`'s `preSend(...)` returning `null`
  drops the message silently with no further callback.** No
  exception, no log, no follow-up. That's why this feature
  pairs the `null` return with an explicit ERROR frame on the
  outbound channel — otherwise the client would see the SEND
  vanish and have no diagnostic.
- **The `Authorization` header from the WebSocket handshake
  survives as a native STOMP header on CONNECT, but only on
  CONNECT — not on subsequent frames.** That's the deliberate
  identity-attachment point; the handshake-time header isn't
  re-sent. This matches the JWT-bearer pattern's posture:
  authenticate once at session start, carry the identity for
  the rest of the lifetime.

## To dig deeper

- [Spring Framework reference — WebSocket Interception](https://docs.spring.io/spring-framework/reference/web/websocket/stomp/interceptors.html)
  — the canonical doc on `ChannelInterceptor.preSend` and the
  inbound/outbound channel split.
- [Spring Security reference — STOMP authentication](https://docs.spring.io/spring-security/reference/servlet/integrations/websocket.html#websocket-authentication)
  — Spring Security's own take on attaching identity to a
  STOMP session. This feature doesn't use the
  `WebSocketMessageBrokerSecurityConfig` because the
  identity-attachment surface is simpler than Spring Security's
  full authorize-message DSL.
- [STOMP 1.2 specification — ERROR frame](https://stomp.github.io/stomp-specification-1.2.html#ERROR)
  — the protocol-level description of ERROR frames and why
  they don't close the session.
- [tsec — Scala JWT library](https://jmcardon.github.io/tsec/docs/jwt-mac.html)
  — the Typelevel parallel for HMAC JWT verification, used in
  the http4s analog above.
- [Socket.IO middleware](https://socket.io/docs/v4/middlewares/)
  — the Node parallel; `io.use` for per-handshake middleware,
  `socket.use` for per-event middleware. The shape that maps
  closest to the two-phase Spring interceptor.

## File map

**Created:**

- `src/main/java/io/github/dariogguillen/chess/websocket/StompAuthInterceptor.java`
  — the interceptor. Constructor-injected `JwtVerifier`,
  `UserRepository`, `GameStore`, and `@Lazy
  @Qualifier("clientOutboundChannel") MessageChannel`.
  Private helpers for token extraction, user loading,
  destination-to-gameId parsing, player matching, and ERROR
  frame construction. Class JavaDoc covers the two-phase
  contract and the design rationale.
- `src/test/java/io/github/dariogguillen/chess/websocket/StompAuthIT.java`
  — 5-case IT. Boots the full Spring context on a random port
  (real WebSocket handshake needed for the `Authorization`
  native header to reach the interceptor). Each case follows
  the plan's enumeration: anonymous CONNECT, valid-JWT
  CONNECT, invalid-JWT CONNECT (the critical regression pin),
  authenticated spoof of opponent's playerId, anonymous
  pin-on-first-use mismatch.
- `notes/20-auth-stomp-trust.md` — this note.

**Modified:**

- `src/main/java/io/github/dariogguillen/chess/config/WebSocketConfig.java`
  — overrides `configureClientInboundChannel(ChannelRegistration)`
  to register `StompAuthInterceptor` at the head of the
  inbound channel. Constructor now takes the interceptor in
  addition to `CorsProperties`. JavaDoc explains the ordering
  relative to the `@EventListener`-driven trackers.
- `docs/architecture.md` — adds a new "WebSocket trust model
  (feature 20)" subsection in the Authentication section,
  describing the two-phase interceptor, the
  anonymous-still-works guarantee, the ERROR-not-disconnect
  choice, the `JwtVerifier` reuse, and the `GameStore`-vs-
  `GameService` decision. Also removes the now-stale "STOMP
  identity verification is feature 20" line from
  "What's not in this feature" and adds the
  rate-limiting/admin-disconnect deferral.
- `README.md` — bumps the test-count claim 207 → 212,
  updates the WebSocket subsection to document the optional
  `Authorization` header on CONNECT and the
  identity-spoof-prevention surface, and softens the "Out of
  scope → Authentication" line to reflect that the auth
  bundle (16-20) is in place and only the future-work
  extensions are deferred.

**Not modified:**

- Any existing STOMP IT (`RoomLifecycleIT`,
  `GameWebSocketIT`, `ViewerCountIT`,
  `DisconnectHandlingIT`, `DisconnectNotificationsIT`). They
  exercise the anonymous flow and all stay green without
  edits — the interceptor's no-JWT path is the regression
  pin that proves bundle decision 7 is enforced.

**Cross-repo:** optional and additive. The frontend MAY
adopt the `Authorization: Bearer <jwt>` native header on its
STOMP CONNECT to identify the session; nothing in the
contract requires it. Until the frontend ships that change,
the existing `X-Player-Id`-style anonymous flow continues
working as before.
