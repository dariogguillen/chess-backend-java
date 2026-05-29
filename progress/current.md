# Current session — auth bundle (feature 20 in progress, last of bundle)

**Status:** in_progress on feature 20 (`auth-stomp-trust`).
**Opened:** 2026-05-28 (continuing the bundle opened 2026-05-27).
**Scope of feature 20:** STOMP `ChannelInterceptor` that validates a
JWT on CONNECT and prevents identity spoofing on SEND / SUBSCRIBE.
Closes the last gap left by features 16–19: REST is auth-aware but
the WebSocket surface still trusts whatever `playerId` the client
sends. After this feature, an authenticated session cannot claim to
be a different user's player, and an anonymous session cannot claim
to be someone else's `X-Player-Id`.

`feature_list.json` snapshot: **28 done, 1 in_progress, 0 pending.**
**This is the last feature of the bundle.**

---

## Why this bundle (carried forward verbatim)

User goal (verbatim 2026-05-27): *"seria opcional, se puede seguir
juegando sin cuenta, pero con una cuenta se pueden revisar las
partidas jugadas por ejemplo"*.

---

## Bundle decomposition (features 16–20, final)

| Priority | ID | Status | One-line goal |
| --- | --- | --- | --- |
| 16 | `auth-core` | done | User entity, Flyway V2, Spring Security base, JWT validation, `GET /api/me`. |
| 17 | `auth-jwt` | done | Email/password register + login → JWT. JWT shape locked. 12-code error enum. |
| 18 | `auth-google-oauth` | done | Google OAuth 2.0 client; success handler redirects with JWT in URL fragment. |
| 19 | `auth-my-games` | done | `GET /api/me/games` paginated. `games.{white,black}_user_id` populated when authenticated. |
| 20 | `auth-stomp-trust` | **in_progress** | STOMP `ChannelInterceptor` — JWT on CONNECT + identity-spoof prevention on SEND/SUBSCRIBE. |

After feature 20: 29 done, 0 in_progress, 0 pending. The auth
bundle closes and the repo returns to maintenance mode (per
[[project-portfolio-complete]]).

---

## Bundle-level technical decisions (carried forward verbatim)

1. **Token transport:** stateless JWT in `Authorization: Bearer`.
2. **JWT algorithm:** HS256 + `AUTH_JWT_SECRET`; 7-day lifetime;
   same `JwtIssuer` / `JwtVerifier` shared across features 17 / 18.
3. **JWT claims:** `sub` = `User.id`, `email`, `iat`, `exp`.
4. **OAuth callback:** backend redirect to frontend with token in
   URL fragment.
5. **CORS:** `allowCredentials` stays false.
6. **Identity linking:** fresh start. User-game link via
   `games.{white,black}_user_id`. No intermediate `players` table.
7. **STOMP auth surface:** anonymous STOMP keeps working (guest
   play); JWT strengthens identity, never gates access. Identity
   spoofing is blocked starting in this feature.

---

## Feature 20 — `auth-stomp-trust` — detailed plan

### Approach

A single `ChannelInterceptor` registered on the client-inbound
channel. The interceptor is the load-bearing piece — every STOMP
frame from a client goes through it before any `@MessageMapping` or
broker dispatch sees it.

Two-phase behaviour:

**Phase 1 — CONNECT.** Inspect the native `Authorization` header on
the STOMP CONNECT frame. If present and the JWT verifies
(`JwtVerifier.verify`), load the User and attach it to the STOMP
session attributes (and as the `Principal` on the
`SimpAttributes`). If absent OR invalid (expired, malformed, bad
signature), **the connection is NOT rejected** — the session stays
anonymous. Anonymous STOMP is preserved by design (bundle decision
7). Only the identity strengthening is gated by JWT validity.

This is a deliberate departure from the standard "JWT or 401"
pattern for REST. Anonymous play is a first-class use case for the
chess product, and rejecting anonymous WebSocket connections would
break guest games immediately.

**Phase 2 — SEND / SUBSCRIBE.** When a frame carries an explicit
`playerId` (either as a STOMP header or inside the payload of a
SEND), the interceptor verifies the claim against the session
identity:

- **Authenticated session:** the claimed `playerId` must correspond
  to a `Player` row whose `userId` matches the session's User. If
  it does not, the interceptor returns an ERROR frame and rejects
  the message (drops it; the broker never sees it). The session
  itself is not closed — the user can correct their client and
  retry.
- **Anonymous session:** the claimed `playerId` must match the
  X-Player-Id-style identity stored in the session. The
  X-Player-Id flow already attaches the player id to the STOMP
  session during the JOIN flow (per feature 11.7's wiring). If it
  does not match, ERROR frame.
- **No `playerId` claim in the frame:** pass-through. The
  interceptor only checks claims; it does not require them.

The "ERROR-but-no-disconnect" choice keeps a buggy frontend
recoverable — one bad frame doesn't kill the whole session, and
the spectator / pure-subscribe case (no playerId claimed) is
untouched.

### Files created or modified, by package

**`io.github.dariogguillen.chess.websocket`** (1 new)
- `StompAuthInterceptor.java` — new. Implements
  `ChannelInterceptor`. Constructor-injected: `JwtVerifier`,
  `UserRepository` (to load `User` by sub from the JWT;
  `JwtAuthenticationFilter` does the same in the REST path).
  Possibly also a `PlayerSessionTracker` lookup if the X-Player-Id
  → session mapping is centralised there.
  - `preSend(message, channel)` is the main hook. Branch on
    `StompCommand`:
    - `CONNECT` → JWT inspection + session-attribute store.
    - `SEND`, `SUBSCRIBE` → identity-spoof check.
    - others (`DISCONNECT`, `UNSUBSCRIBE`, etc.) → pass-through.
  - Helper methods kept private; documented with JavaDoc covering
    the two-phase contract.

**`io.github.dariogguillen.chess.config`** (1 modified)
- `WebSocketConfig.java` — modified. Inside
  `configureClientInboundChannel(ChannelRegistration registration)`,
  register the new interceptor:
  `registration.interceptors(stompAuthInterceptor, ... existing
  interceptors ...)`. Order matters: the auth interceptor MUST run
  BEFORE the existing trackers (`PlayerSessionTracker`,
  `ViewerCountTracker`) so the identity is in place when those
  trackers read session attributes. Implementer verifies the
  current ordering and inserts at the right position.

**`io.github.dariogguillen.chess.websocket`** (possibly modified)
- The implementer may discover that the existing event handlers
  (`@MessageMapping`-annotated controllers somewhere; verify) need
  to consult the session's authenticated user. If the only check
  needed is in the interceptor (which is where all the gating
  happens), no further changes. Document any propagation in the
  feature note.

**Tests:**

- `src/test/java/.../websocket/StompAuthIT.java` — new. Cases (5):
  1. `stompConnect_withoutAuthHeader_succeedsAnonymous` — STOMP
     CONNECT with no `Authorization` header. Connection
     establishes; session is anonymous; SUBSCRIBE to a topic
     succeeds; a SEND without a `playerId` claim succeeds. This
     is the regression pin for guest play.
  2. `stompConnect_withValidJwt_succeedsAndIdentifiesSession` —
     CONNECT with `Authorization: Bearer <validJwt>` for user A.
     Connection establishes; the session is identified as A.
     Subsequent SEND with `playerId` matching A's player succeeds.
  3. `stompConnect_withInvalidJwt_succeedsButAnonymous` —
     CONNECT with an expired or malformed JWT. Connection STILL
     ESTABLISHES (anonymous). SUBSCRIBE to a topic succeeds; a
     SEND without a `playerId` claim succeeds. This is the
     critical pin: bad JWT does NOT break guest play; it only
     fails to identify the session.
  4. `stompSend_authenticatedSessionWithOpponentsPlayerId_rejected` —
     User A is authenticated on the STOMP session. A SEND frame
     carries `playerId = B's player`. Interceptor produces an
     ERROR frame; the message is NOT delivered to the broker;
     the session stays open.
  5. `stompSend_anonymousSessionWithMismatchedPlayerId_rejected` —
     Anonymous session whose X-Player-Id session attribute is
     player X. A SEND frame carries `playerId = Y` (different).
     ERROR frame; message dropped.

If the test framework cannot easily synthesize STOMP frames at
this level of granularity, the implementer may need a small test
helper. Spring's `WebSocketStompClient` + a custom
`StompFrameHandler` is the standard approach (the project
likely already has this scaffolding in
`RoomLifecycleIT` / similar).

**Modified ITs (regression — expectations):**
- `RoomLifecycleIT` and any existing STOMP IT must STAY GREEN
  WITHOUT MODIFICATION. They exercise the anonymous flow; the
  new interceptor's no-JWT path must keep them intact. If they
  break, the interceptor is too strict — fix the interceptor,
  not the tests.

**Docs:**
- `docs/architecture.md` — Authentication section gains a new
  subsection "WebSocket trust model" describing the two-phase
  interceptor, the "anonymous still works" guarantee, and the
  "ERROR-but-no-disconnect" choice.
- `README.md` — Authentication subsection notes that the
  WebSocket surface is now identity-aware; static test-count
  claim bumped 207 → 212 (or whatever lands).

**`notes/20-auth-stomp-trust.md`** (new)
- Follows `_template.md`. Java/Spring concepts: `ChannelInterceptor`
  hook points, `StompHeaderAccessor` and the native-header layer,
  `SimpAttributes` session storage, the `MessageChannel` vs
  `SubscribableChannel` distinction, why STOMP ERROR is not the
  same as connection close. Cross-ecosystem: http4s WebSocket
  middleware composition; `cats.effect.std.Mutex` for per-session
  state; fs2 Pipe vs Spring's interceptor chain mental model.
- Decisions: (a) bad JWT → anonymous (not rejection), (b)
  identity-spoof → ERROR frame (not disconnect), (c) where in the
  WebSocketConfig interceptor chain the new one slots.
- Gotchas: STOMP frame inspection at the `Message<?>` level (the
  payload type is byte[] for SEND; header inspection is the only
  reliable way to read the `playerId` claim); session
  identification timing (CONNECT must complete before the broker
  sends the CONNECTED frame).

### Verification

`./init.sh` is the canonical gate. New ITs: 5 in `StompAuthIT`.
Expected new total: 212 (97 unit + 115 IT). Delta +5 IT, 0 unit.

Reviewer's extra checks:
- The anonymous flow regression — `RoomLifecycleIT` and any
  existing STOMP test stays green unchanged. Confirm via
  `git diff --name-only src/test`.
- The interceptor's "bad JWT → anonymous" behaviour is genuinely
  tested with an actually-malformed JWT (not just a missing
  header).
- The ERROR frame on identity-spoof is observable client-side:
  the test client must receive the ERROR frame and the rejected
  SEND must NOT reach any `@MessageMapping` handler.
- The interceptor runs BEFORE `PlayerSessionTracker` /
  `ViewerCountTracker` in the channel registration; reading
  `WebSocketConfig.configureClientInboundChannel` confirms the
  order.
- No PII in interceptor logs (same standard as features 17–18).

### Cross-repo coordination

**Optional / additive.** The frontend can OPTIONALLY start
attaching `Authorization: Bearer <jwt>` to its STOMP CONNECT to
identify the session. Without it, the existing X-Player-Id flow
continues working. So the frontend can adopt this at its own
pace; the backend ships independently. The contract change for the
frontend is documented in `docs/architecture.md`.

### Java / Spring concepts to highlight

- `ChannelInterceptor.preSend` semantics — return `null` to
  drop the message; return the original to pass through;
  return a modified message to forward a transformed version.
- The STOMP "ERROR frame is not disconnect" pattern — sending
  an ERROR frame back through the channel allows the client to
  observe the rejection without losing the connection.
- `SimpAttributes` vs `WebSocketSession` attributes — where
  custom session state lives in a STOMP-over-WebSocket setup.
- Native headers vs STOMP headers — the `Authorization` header
  from the WebSocket handshake survives as a native header on
  the CONNECT frame; `StompHeaderAccessor.getNativeHeader(...)`
  is the read path.
- Why the JWT validation is the SAME `JwtVerifier` used by REST
  — single source of truth for token correctness.

### What feature 20 does NOT do

- Does NOT reject STOMP CONNECT for any reason. Anonymous and
  authenticated both reach a CONNECTED state.
- Does NOT police topic subscription (any subscribe is allowed
  if the session is connected — even spectators on a stranger's
  game; that's the existing model).
- Does NOT introduce rate-limiting (future-work candidate).
- Does NOT add a "force-disconnect" admin endpoint (operational
  scope, separate feature).

---

## Carried over from 2026-05-25 closure (operator follow-ups)

These still apply and become the final pre-merge checklist for
the bundle:

- **Rotate the RDS master password.**
- **Configure branch protection on `main`.**
- **Replace the static test-count claim** in `README.md`. After
  feature 20 the claim will read 212 (or near it); replacing
  with a dynamic count or removing it closes this carry-over.
- **Google OAuth Client ID + env vars** (feature 18's addition).

---

## Leader notes for the next handoffs

- Feature 20 plan needs user approval per `leader.md` before
  delegation. Last delegation of the bundle.
- When feature 20 closes, the bundle is complete. Counts move
  to 29 done, 0 in_progress, 0 pending. The leader's closing
  tasks:
  1. Flip `auth-stomp-trust` to done.
  2. Append the feature-20 history entry.
  3. Append a **"Auth bundle complete"** milestone marker in
     `progress/history.md` (matching the voice of the
     2026-05-25 portfolio-closure marker).
  4. Replace `progress/current.md` with a closed-session note
     (the repo returns to maintenance mode; the
     [[project-portfolio-complete]] memory should be updated
     to reflect the new bundle as completed).
- Per [[feedback-flag-untracked-files-at-close]]: at feature 20
  close, flag the new `StompAuthInterceptor.java` + the new IT
  file. No new package (lives under existing `websocket/` and
  test `websocket/`).
