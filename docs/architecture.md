# Architecture

This document describes what "good work" looks like in this project. It
covers the architectural decisions, the reasoning behind them, and the
boundaries between components. If a decision is not documented here and
not obvious from the code, prefer **the simplest standard Spring Boot
approach** and surface the question with the user.

---

## High-level shape

A single Spring Boot servlet application. Not microservices, not modular
monolith. One process, one deployable, one `pom.xml`.

The application exposes:

- **REST endpoints** under `/api/...` for state-changing actions
  (create room, make a move, query state).
- **STOMP over WebSocket** at `/ws` for real-time broadcasts to clients
  in a room.
- A **health endpoint** at `/api/health` for liveness and version info.

The application depends on two external systems:

- **PostgreSQL** for durable state (completed games, players).
- **Redis** for ephemeral state (active rooms, active games, player
  connection state).

Both are managed via Docker Compose locally and via Testcontainers in
integration tests.

## Layered architecture

The dependency direction is strictly top to bottom. Lower layers do not
know about higher layers.

```
web / websocket
       │
       ▼
    service
       │
       ▼
  persistence / cache / domain (chesslib)
```

- **`web/`** holds REST controllers. Controllers translate HTTP to
  service calls and back. They never call repositories directly.
- **`websocket/`** holds STOMP controllers. Same role for WebSocket
  traffic.
- **`service/`** holds application services. Services orchestrate
  domain operations, validation, persistence, and broadcasting. This
  is where transactional boundaries live.
- **`domain/`** holds entities, value objects, and pure domain logic
  (e.g., wrappers over chesslib). No Spring annotations here if
  possible.
- **`persistence/`** holds JPA repositories and Postgres-backed code.
- **`cache/`** holds Spring Data Redis repositories and caching
  utilities.
- **`exception/`** holds the exception hierarchy and the global
  `@RestControllerAdvice`.
- **`config/`** holds `@Configuration` classes (WebSocket, security
  later, etc.).

`service/` may introduce its own value types when the domain shape does
not match the service's computational needs. For example, `ChessRules`
operates on a service-level `GameState` record (`startingFen + history +
cached current views`) rather than the domain `Game`, because chess-rule
decisions need position history but not player identity. Such
service-level types live alongside the service that consumes them and
are mapped to and from domain types at the service boundary.

`service/` also holds the **storage seams** for active state: small
interfaces like `RoomStore` and `GameStore`. Feature 8 wired the
Redis-backed implementations — `cache/RedisRoomStore` and
`cache/RedisGameStore` — as the registered beans, replacing the day-one
`InMemory*` adapters without any change to `RoomService` or consumers
above the service layer. Putting the interfaces in `service/` and their
implementations in `cache/` keeps the port next to its sole consumer
while the adapters live alongside other Redis-backed code. The TTL,
key schema, and atomicity model for the Redis swap are documented in
the "Active state in Redis" subsection under "State strategy" below.

### Deployment artifact

The deployment artifact is a single Docker image produced by a
multi-stage build (`Dockerfile`): a JDK + Maven wrapper builder stage
that runs `spring-boot:repackage`, and a JRE-only runtime stage that
ships nothing but the resulting fat jar. `application.yml` uses the
env-var-with-default pattern (`${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/chess}`,
etc.) so the same image runs under three contexts — Testcontainers in
integration tests (via `@ServiceConnection` overrides),
`docker-compose.yml` for local stack runs (via explicit env vars
pointing at in-network hostnames), and production (features 7.5 / 7.7)
without rebuilding. `init.sh` deliberately stays scoped to "compile +
lint + test" and does **not** build the image; feature 7.7's CI workflow
will add a Docker smoke test in GitHub Actions.

The production environment provisioned by feature 7.5 is AWS Free Tier
in `us-east-2`: an EC2 t3.micro (Ubuntu 24.04 LTS) sits behind a
native-systemd Caddy that terminates TLS via Let's Encrypt for the
Duck DNS hostname `chess-backend.duckdns.org` and reverse-proxies to
the Spring Boot container on `localhost:8080`. Postgres lives in an
RDS db.t3.micro instance reachable only from the EC2 Security Group;
Redis is self-hosted in a Docker container on the same EC2 (ElastiCache
is not in the Free Tier). The production compose file
`docker-compose.prod.yml` at the repo root encodes this shape: no
Postgres service, Redis 7-alpine, the app image bound to `127.0.0.1:8080`
so Caddy is the sole inbound path. All Terraform sources live in
`infra/`; the manual deploy procedure is in `docs/deploy-runbook.md`.

### Deploy automation

Pushes to `main` trigger `.github/workflows/deploy.yml`, which is the
sole automated path to production. The workflow authenticates to AWS
via **OIDC** — a `token.actions.githubusercontent.com` federated
identity provider plus an `aws_iam_role.github_actions` role scoped to
`repo:dariogguillen/chess-backend-java:ref:refs/heads/main` — so there
are no static AWS access keys anywhere in the repo, in GitHub secrets,
or on developer machines. The job runs `./init.sh` (the same verifier
used locally), builds the production Docker image, tags it with both
the commit SHA and `latest`, and pushes both tags to the ECR
`chess-backend` repository.

After the push, the workflow SSHes into the EC2 host as the dedicated
`deploy` user (a CI-only SSH key, separate from the operator key used
for the runbook) and runs `docker compose pull && docker compose up
-d` against `docker-compose.prod.yml`. The EC2 pulls the new image
from ECR using an IAM **instance profile** with
`AmazonEC2ContainerRegistryReadOnly` — the host never sees long-lived
registry credentials either. A smoke test hitting
`https://chess-backend.duckdns.org/api/health` gates the workflow's
success: a non-200 response fails the run so the deploy is visibly
broken rather than silently rolled forward. Step-by-step operator
recovery still lives in `docs/deploy-runbook.md`; this section only
records the structural shape.

### API contract

The REST surface is documented via an OpenAPI 3 spec generated at
runtime by **springdoc-openapi**. The spec is served at
`/v3/api-docs` (JSON) and `/swagger-ui.html` (interactive UI). The
source of truth lives in the controllers themselves: `@Tag`,
`@Operation`, and `@ApiResponse` annotations on each `@RestController`,
plus selective `@Schema` annotations on the record DTOs. A
top-level `@Bean OpenAPI` in `config/` contributes the title,
description, and build version. The WebSocket / STOMP surface is
intentionally out of springdoc's scope; it is documented in the
"STOMP API contract" section below.

#### Error codes

All 4xx responses use the `ErrorResponse` schema. The `error` field is
constrained to the following enum, captured in the OpenAPI spec as
`components.schemas.ErrorResponse.properties.error.enum`. Clients
consuming the spec (e.g. via `openapi-typescript`) get a TypeScript
union literal automatically, without having to maintain a parallel
list on the client side.

| Code | HTTP | Source |
|---|---|---|
| `ROOM_NOT_FOUND` | 404 | `RoomNotFoundException` |
| `ROOM_FULL` | 409 | `RoomFullException` |
| `GAME_NOT_FOUND` | 404 | `GameNotFoundException` |
| `GAME_ALREADY_ENDED` | 409 | `GameAlreadyEndedException` |
| `ILLEGAL_MOVE` | 422 | `IllegalMoveException` |
| `NOT_YOUR_TURN` | 422 | `NotYourTurnException` |
| `VALIDATION_FAILED` | 400 | `MethodArgumentNotValidException` (`@Valid` failure) |
| `MALFORMED_REQUEST` | 400 | `HttpMessageNotReadableException` (unparseable JSON body) |
| `MISSING_HEADER` | 400 | `MissingRequestHeaderException` (e.g. `X-Player-Id` missing) |
| `AUTHENTICATION_REQUIRED` | 401 | `AuthEntryPoint` (Spring Security 401 entry point) |
| `EMAIL_ALREADY_TAKEN` | 409 | `EmailAlreadyTakenException` |
| `INVALID_CREDENTIALS` | 401 | `InvalidCredentialsException` |

The first six codes are produced mechanically by `GlobalExceptionHandler`'s
`codeOf(ChessException)` derivation — simple class name minus the
trailing `Exception` suffix, converted from camelCase to
UPPER_SNAKE_CASE. The next three (`VALIDATION_FAILED`,
`MALFORMED_REQUEST`, `MISSING_HEADER`) are hardcoded literals in the
framework-exception handlers because Spring's own exception types do
not follow our naming. The last three landed with feature 17 — the
two `*Exception` subclasses follow the mechanical derivation,
`AUTHENTICATION_REQUIRED` is emitted by `AuthEntryPoint` rather than
`GlobalExceptionHandler` (it is the only 401-from-security-filter case
today), but they share the same `ErrorResponse` envelope.

Adding a new code requires updating both `GlobalExceptionHandler` and
`ErrorResponse.error`'s `@Schema(allowableValues = {...})`. The
`OpenApiIT` drift canary asserts the enum array matches the expected
set exactly, so forgetting one of the two halves fails the build.

#### Room read endpoint

`GET /api/rooms/{id}` returns the current state of a room, used by
the frontend either as the primary discovery mechanism (Player A
polls until `gameId` is non-null and then transitions to the game
flow) or as a fallback for STOMP late subscribers, who cannot
replay events on `/topic/rooms/{roomId}`. The two mechanisms ship
together: STOMP gives sub-second push UX when the subscription
timing works, polling is the safety net when it does not.

```
GET /api/rooms/{id}

200 OK
{
  "roomId": "K7M3X9",
  "players": [
    { "id": "<uuid>", "displayName": "Alice", "role": "WHITE" },
    { "id": "<uuid>", "displayName": "Bob",   "role": "BLACK" }
  ],
  "gameId": "<uuid>",
  "status": "ACTIVE"
}
```

- **`status`** — backend-native enum: `WAITING_FOR_PLAYER`,
  `ACTIVE`, or `CLOSED`. No mapping to a presentation-specific
  vocabulary; the frontend uses the literals directly.
- **`players`** — 1 element while `WAITING_FOR_PLAYER`, 2 elements
  while `ACTIVE`. Role is **derived at the web boundary** from the
  player's position in the array: index 0 is `WHITE` (the
  creator), index 1 (when present) is `BLACK` (the joiner). The
  domain `Player` record has no `role` field — the deliberate
  trade-off keeps the domain minimal at the cost of a
  position-sensitive mapper.
- **`gameId`** — `null` while `WAITING_FOR_PLAYER` (no game has
  been created yet); non-`null` once `ACTIVE`.
- **404 `ROOM_NOT_FOUND`** — when the id does not match any room.
  Reuses the existing error code from the 9-code allowlist; no
  expansion.
- The path id is **case-insensitive**; the canonical uppercase form
  is returned in the body (matches the `POST /api/rooms/{id}/join`
  convention).
- **No path-level auth** — the room id is the shared secret, same
  posture as the rest of the API.

## Authentication

Feature 16 (`auth-core`) lands the foundation of the auth bundle
(features 16-20). This section records the bundle-level shape and the
state after feature 16 ships. Subsequent features in the bundle
(issuance, OAuth, "my games", STOMP trust) layer on top without
revisiting these decisions.

**Bundle scope.** Authentication is **optional**. Guest play stays
open on every existing surface — `POST /api/rooms`, `POST
/api/rooms/{id}/join`, `GET /api/rooms/{id}`, `POST /api/games`, `GET
/api/games/{id}`, `POST /api/games/{id}/moves`, `GET
/api/players/{id}/games`, and the STOMP `/ws` handshake all stay
anonymous. An account unlocks "review my past games" (feature 19) and
is the foundation for future per-user features. Confirmed with the
user 2026-05-27: *"seria opcional, se puede seguir jugando sin cuenta,
pero con una cuenta se pueden revisar las partidas jugadas"*.

### The `User` aggregate

A `User` represents an account: email, display name, and either a
BCrypt password hash (feature 17) or a Google `sub` claim (feature
18) — both nullable independently because the two registration paths
produce different shapes. Mapped to the `users` table created by
`V2__create_users_and_game_user_links.sql`. The `email` column has a
DB-level `UNIQUE` constraint; application code normalises to
lowercase before write so case-insensitive uniqueness is enforced at
the application boundary without Postgres CITEXT. The `google_sub`
column has a partial unique index `WHERE google_sub IS NOT NULL` so
the index ignores email-only users. Column caps are derived from the
relevant standards: `email` is `VARCHAR(254)` (RFC 5321 path max),
`password_hash` is `VARCHAR(60)` (BCrypt's fixed-length output),
`google_sub` is `VARCHAR(255)` (Google's documented `sub` upper
bound), `display_name` is `VARCHAR(100)` to match
`games.{white,black}_display_name`.

`User` is a JPA entity in the `domain` package, not a record. JPA
requires a no-args constructor and writable fields; records satisfy
neither. Mutability is contained: setters are package-private, so
only the `domain` and `persistence` packages can mutate an instance.

### Fresh-start identity model

`User.id` is the new canonical identity for authenticated users.
The link between a `User` and the games they played lives as **two
nullable FK columns directly on the `games` table** —
`white_user_id` and `black_user_id`, each `UUID NULL REFERENCES
users(id)`. There is no intermediate `players` table. This mirrors
V1's deliberate denormalisation
(`white_player_id` + `white_display_name`, same on the black side):
adding a `players` row would only duplicate the (UUID + display
name) snapshot and force a join on every history query — exactly
the shape V1 was designed to avoid.

The historical `games.white_player_id` / `games.black_player_id`
columns are kept **unchanged and without a FK to users(id)**. They
are the audit-time identity snapshot — the per-session player UUID
minted by `RoomService` when a guest creates or joins a room — and
they remain unconstrained UUIDs. Even when an authenticated user
plays a game, their `*_player_id` is the per-session identity,
independent of `users.id` by design. The display name surfaces
(`games.{white,black}_display_name`) similarly stay frozen as the
audit-correct name at game time, never rewritten by a future user
rename: same shape as Lichess game records and GitHub commit author
names.

Three rules govern the fresh-start model:

1. Anonymous games created before feature 17 ships stay anonymous
   forever — `white_user_id` and `black_user_id` are NULL and no
   backfill is ever attempted.
2. Anonymous games created during features 16-18 also stay
   anonymous: feature 16 doesn't issue tokens, and features 17-18
   issue tokens but feature 19 is the one that wires
   authenticated game creation.
3. Starting in feature 19, authenticated `POST /api/games` writes
   the matching `white_user_id` or `black_user_id` so the user's
   history query (`GET /api/me/games`) can find them via a direct
   `WHERE white_user_id = :userId OR black_user_id = :userId`.

Account linking ("I had a guest game last week, claim it to my new
account") is **explicitly out of scope** for the whole bundle.

### JWT model

- **Algorithm:** HS256 with a shared secret read from
  `AUTH_JWT_SECRET`. Symmetric is fine because issuer = verifier
  (same backend). Production fails fast at boot if the env var is
  unset; the test profile provides a fixed test secret in
  `src/test/resources/application-test.yml`.
- **Lifetime:** 7 days (`auth.jwt.expiry-seconds = 604800`). No
  refresh tokens, no revocation list — a 7-day blast radius is
  the portfolio-scale trade-off.
- **Claims:** `sub = User.id` (UUID string), `email`, `iat`, `exp`.
  No roles or authorities — the only role today is "authenticated
  user".
- **Transport:** `Authorization: Bearer <token>` on every
  request. Stateless: no session cookies, no `HttpSession`. CSRF
  protection is safely disabled because the credential is a
  script-set header, not a browser-attached cookie.
- **CORS:** `allowCredentials` stays `false` (cookies were never
  in play); the `Authorization` header is on the REST CORS
  allow-list (added by this feature alongside `X-Player-Id` from
  feature 11.7).

### Spring Security wiring

The `SecurityConfig` bean configures a `SecurityFilterChain` in the
Spring Security 6+ idiom — no deprecated
`WebSecurityConfigurerAdapter`. Key elements:

- `SessionCreationPolicy.STATELESS` — no `HttpSession` created or
  read.
- `JwtAuthenticationFilter` inserted before
  `UsernamePasswordAuthenticationFilter` — the canonical hook
  point recommended by the Spring Security reference for custom
  token filters.
- Anonymous allow-list pinned in code: the live guest play
  surface, the operational endpoints (`/api/health`,
  `/actuator/**`, `/v3/api-docs/**`, `/swagger-ui/**`), the STOMP
  handshake (`/ws/**`), and every `OPTIONS *` preflight.
  Everything else requires auth — today that means `GET /api/me`,
  the only authenticated route until feature 17 lands issuance.
- `HttpStatusEntryPoint(401)` — unauthenticated requests to
  protected endpoints return `401 Unauthorized`, not `403`. The
  body is empty by design at this stage; feature 17 may wrap the
  response in `ErrorResponse` if needed.
- `BCryptPasswordEncoder` bean exposed now — unused this feature
  but pre-wired so feature 17's `AuthService` can inject it as a
  pure-additive change.

### `GET /api/me`

Returns 200 with `{ id, email, displayName }` on a valid JWT; returns
401 (with a structured `ErrorResponse` body — see "401 entry point"
below) on a missing, malformed, expired, or differently-signed JWT.
Used as the frontend's "is my stored token still valid?" probe and as
the regression-locking surface for the auth filter chain.

### JWT issuance (feature 17)

Feature 17 (`auth-jwt`) lands the issuance side and the two
user-visible endpoints that produce tokens:

- **`POST /api/auth/register`** — `{ email, password, displayName }`
  → `201` with `{ token, user: { id, email, displayName } }`. The
  email is normalised to lowercase, the password is BCrypt-hashed
  (the encoder bean wired by feature 16, default cost factor 10),
  and the JWT is minted from the freshly created `User`. The whole
  flow is `@Transactional`: the application-level
  `UserRepository.findByEmail` uniqueness check and the insert run
  in one transaction; the database-level `UNIQUE` constraint on
  `users.email` closes the race between two concurrent registrations
  by translating the duplicate insert into
  `DataIntegrityViolationException`, which the service catches and
  rethrows as `EmailAlreadyTakenException` (409 /
  `EMAIL_ALREADY_TAKEN`). Validation failures (malformed email,
  password outside 8-72 chars, blank display name) surface as 400 /
  `VALIDATION_FAILED` via the existing
  `MethodArgumentNotValidException` handler.

- **`POST /api/auth/login`** — `{ email, password }` → `200` with the
  same `{ token, user }` envelope. The email is normalised the same
  way; the BCrypt encoder's `matches(rawPassword, storedHash)` does
  the constant-time comparison. The failure path is uniform: an
  unknown email and a wrong password both throw
  `InvalidCredentialsException` (401 / `INVALID_CREDENTIALS`) with
  the same message ("Invalid email or password"). The service also
  runs the BCrypt comparison against a pre-computed dummy hash on
  the unknown-email branch so the response time does not leak
  whether the email exists.

- **Password-policy leak avoidance.** `RegisterRequest.password`
  carries `@Size(min = 8, max = 72)`; `LoginRequest.password`
  carries no `@Size` annotation. A wrong-length password on login
  is a credentials error (401), not a validation error (400) —
  surfacing "password too short" on login would tell an attacker the
  policy bounds, which is one bit of information they should not
  get from a failed login attempt. The 72-byte cap on register
  matches BCrypt's input block-size limit (passwords longer than 72
  bytes are silently truncated by the algorithm).

The issuance side uses a `JwtIssuer` bean — counterpart to feature
16's `JwtVerifier` — both classes derive the HS256 `SecretKey` from
`AuthProperties.secret` via the same `Keys.hmacShaKeyFor` call, so
their key bytes are identical and the round-trip is byte-for-byte
guaranteed. The integration test `AuthEndpointsIT.roundTrip_*` pins
this: a token minted by `/api/auth/login` is accepted by feature
16's `JwtAuthenticationFilter`, and a subsequent `GET /api/me` with
that token returns the same user. `JwtIssuer` injects the
application's `Clock` bean so the `iat` / `exp` claims are testable
(swap in `Clock.fixed(...)` via a `@Primary` test bean to assert
expiry behaviour).

### 401 entry point

Feature 17 swapped feature 16's `HttpStatusEntryPoint(401)`
placeholder for a custom `AuthEntryPoint` that writes a structured
`ErrorResponse` body to every 401 emitted by the security filter
chain. The body shape is the same envelope `GlobalExceptionHandler`
produces for the other 4xx codes — `{ error, message, timestamp }`
— with `error = "AUTHENTICATION_REQUIRED"` and a generic message
("Authentication is required to access this resource."). The
message is intentionally uniform: missing header, expired token, and
forged token all surface identically, mirroring
`JwtAuthenticationFilter`'s policy of not logging the specific JWT
failure reason in the response.

### What's not in this feature

- **Google OAuth** — feature 18 (`auth-google-oauth`).
- **Per-user game history** — feature 19 (`auth-my-games`).
- **STOMP identity verification** — feature 20 (`auth-stomp-trust`).
- **Refresh tokens, password reset, email verification, 2FA,
  account linking** — out of scope for the whole bundle.

## CORS

Both the REST surface (`/api/**`) and the STOMP handshake (`/ws`)
draw their allowed-origin list from a single property,
`chess.cors.allowed-origin-patterns`, bound to a
`@ConfigurationProperties("chess.cors")` record (`CorsProperties`).
REST consumes it via `CorsConfig`'s `WebMvcConfigurer`; STOMP
consumes it via `WebSocketConfig`'s `setAllowedOriginPatterns`.
Centralising the list this way is what prevents the drift the
previous two-hardcoded-copies shape would invite the moment a
third origin (e.g. a Vercel preview deploy) needs to be allowed.

### Allowed origin patterns

The default list, embedded as the env-var default in
`application.yml`:

- `https://chess-frontend-52i.pages.dev` — production frontend on
  Cloudflare Pages.
- `http://localhost:*` — development frontend on any localhost
  port (5173 for Vite, 3000 for CRA, etc.).

Override without recompiling in production by setting
`CHESS_CORS_ALLOWED_ORIGIN_PATTERNS` on the EC2 host to a
comma-separated list. Spring binds the env-var string to
`List<String>` natively.

We use `allowedOriginPatterns` (not `allowedOrigins`) because
Spring 6+ disallows `*` with credentials and requires the patterns
form whenever a wildcard like `http://localhost:*` is in play. The
WebSocket side has the same constraint, so the two layers agree
on the alphabet for free.

### REST policy (`/api/**`)

- **Methods:** `GET`, `POST`, `PUT`, `DELETE`, `OPTIONS`. GET and
  POST cover today's surface; PUT and DELETE futureproof for the
  RESTful CRUD that may land later (e.g. an explicit close-room
  endpoint).
- **Headers:** `Content-Type`, `Accept`, `X-Player-Id`. The first
  two cover JSON request/response; `X-Player-Id` is required by
  `POST /api/games/{id}/moves` to identify the mover, so it must be
  on the allow-list for the browser preflight to succeed cross-
  origin. No `Authorization` — the codebase has no auth yet, and
  allow-listing it preemptively would be dead config that implies
  functionality we have not built. The future auth feature owns
  adding the header to the list as part of its own change.
- **`allowCredentials: false`** — the API is stateless JSON;
  identity travels in request bodies and path parameters
  (`X-Player-Id`, `playerId` in payloads), never in cookies.
  Setting `false` keeps the security posture honest and prevents
  a future change from silently sharing cookies cross-origin.
- **`maxAge: 3600`** — caches the preflight on the browser side
  for one hour, the standard conservative value.

### STOMP policy (`/ws`)

The STOMP handshake reuses the same allowed-origin-patterns list
through `CorsProperties`. STOMP frames themselves are not subject
to CORS once the WebSocket upgrade has completed; the policy gates
the initial HTTP handshake only.

### Reverse proxy

Caddy in production (`reverse_proxy localhost:8080`) does **not**
inject CORS headers. The Spring layer is the sole emitter of
`Access-Control-Allow-*` on every response. The reverse proxy stays
purely a TLS terminator + path forwarder, with no policy of its
own — single source of truth holds across operational layers, not
just inside the application.

## STOMP API contract

REST is the entry point for **mutations** (create room, join room,
apply a move). STOMP is the side channel for **read-only push** —
after a move is accepted on the REST side, the server broadcasts a
`MoveEvent` to every subscriber of that game's topic. This section
is the source of truth for the STOMP surface; the `chess-frontend`
repo mirrors it in its own `docs/architecture.md` when it reaches
its feature 5 (`stomp-live-updates`).

**Codebase-wide design rule for STOMP event shape:**
*polymorphic topics get the discriminator; single-event topics stay
flat.* A topic that can carry more than one event variant
(`/topic/rooms/{roomId}`, `/topic/games/{gameId}`) is modelled as a
`sealed interface` (`RoomEvent`, `GameStateEvent`) and every variant
carries an explicit `type: "..."` field set by a convenience
constructor (no `@JsonTypeInfo`). A topic that can only ever carry
one shape (today: `ViewerCountEvent` on
`/topic/games/{gameId}/viewers`) stays flat — the discriminator would
be dead weight. This rule was introduced by feature 9.5 on the rooms
topic and lifted to a codebase-wide convention by feature 11.5 when
the games topic gained `PlayerDisconnectedEvent` /
`PlayerReconnectedEvent` and was retrofitted with the family.

### Endpoint and broker

- **WebSocket endpoint:** `/ws`. Clients perform a STOMP `CONNECT`
  over native WebSocket. SockJS fallback is **not** enabled —
  modern browsers plus the targeted `@stomp/stompjs` frontend
  client handle native WebSocket fine, and SockJS would add
  surface area we do not need.
- **Broker:** Spring's in-process `SimpleBroker`, registered on
  the `/topic` prefix. Subscriptions and fan-out happen inside
  the application process — sufficient for a single-instance
  deployment. Scaling out to multiple instances would require an
  external broker (RabbitMQ, ActiveMQ, or equivalent) so that a
  broadcast on instance A reaches subscribers connected to
  instance B. That is a documented constraint to revisit, not a
  current concern.
- **Application destination prefix:** `/app`, registered for
  future-proofing. This feature does not introduce any client-to-
  server STOMP messages — `MoveEvent` is the only traffic, and it
  flows server-to-client. The prefix is in place so a future
  `@MessageMapping` endpoint can land without a config change.

### Allowed origins (CORS for the WebSocket handshake)

The `/ws` endpoint's allowed origin patterns are sourced from the
same `chess.cors.allowed-origin-patterns` property the REST
{@code /api/**} filter consumes — see the "CORS" section above for
the full list, the env-var override, and the rationale for
`setAllowedOriginPatterns` over `setAllowedOrigins`. `WebSocketConfig`
constructor-injects `CorsProperties` and passes
`props.allowedOriginPatterns().toArray(...)` to the STOMP endpoint
registration; there is no second copy of the list anywhere.

### Subscriptions

Clients subscribe to **`/topic/games/{gameId}`** — one logical
channel per game. The topic is **polymorphic**: per the codebase-wide
rule above, every event variant on it carries an explicit `type`
discriminator field. After feature 11.5, the variants are `MoveEvent`
(per accepted move), `GameAbandonedEvent` (terminal-by-timeout),
`PlayerDisconnectedEvent` (mid-grace UX, opens the "reconnecting"
banner), and `PlayerReconnectedEvent` (closes the banner) — modelled
together as the `GameStateEvent` sealed family. Documented in
the "`GameStateEvent` family" subsection below.

Clients also subscribe to **`/topic/rooms/{roomId}`** for room
lifecycle events. The topic is **polymorphic** for the same reason:
today the only variant is `RoomJoinedEvent`; future features layer
in `RoomClosedEvent`, `PlayerLeftEvent`, etc. without changing the
topic shape. Every variant carries an explicit `type` discriminator
field in the JSON payload so subscribers can branch without
polymorphic Jackson machinery:

```json
{
  "type": "ROOM_JOINED",
  "roomId": "K7M3X9",
  "gameId": "0d52a8a0-aaaa-bbbb-cccc-ddddeeee0000",
  "blackPlayer": {
    "id": "8b3c1f04-1234-5678-9abc-def012345678",
    "displayName": "Bob"
  }
}
```

The Java model is a `sealed interface RoomEvent permits ...` with
the discriminator set explicitly in each variant's canonical
constructor (no `@JsonTypeInfo`). This is the same design rule
applied to `GameStateEvent` on `/topic/games/{gameId}` and stated
codebase-wide at the top of this section.

`RoomJoinedEvent` is published from `RoomService.joinRoom` the
instant the room transitions from `WAITING_FOR_PLAYER` to `ACTIVE`
and both Redis writes (room + game) are durable. The broadcast is
**fire-and-forget**, outside the atomic block, with the same
`try/catch + WARN log` policy as `MoveEvent`. The canonical
subscriber is Player A (the room creator), who subscribes right
after `POST /api/rooms` returns: receiving the event is how A
learns the `gameId` and can transition to
`/topic/games/{gameId}`. Subscribers that arrive **after** the
join miss the event entirely — there is no replay. The fallback in
that case is the `GET /api/rooms/{id}` REST endpoint (documented
in the "API contract" section above), which carries the same
`gameId` and the rest of the room state for reconcile.

### `GameStateEvent` family

`/topic/games/{gameId}` is a polymorphic topic. The Java model is a
`sealed interface GameStateEvent permits MoveEvent,
GameAbandonedEvent, PlayerDisconnectedEvent, PlayerReconnectedEvent`
with the discriminator set explicitly in each variant's convenience
constructor (no `@JsonTypeInfo`). Every variant carries `type:
"..."` and `gameId: <UUID>` as its first two fields; subscribers
branch on `event.type` and parse the rest accordingly. Feature 11.5
established this family by retrofitting `MoveEvent` and
`GameAbandonedEvent` (both already on the topic from features 6 and
11) with the discriminator field and adding the two new mid-grace
variants.

The retrofit is **backward-compatible** for the deployed frontend:
the new `type` field is an additive change. Jackson's
`FAIL_ON_UNKNOWN_PROPERTIES = false` default means consumers that
do not declare a `type` field on their typed payload class ignore
it, and shape-based discrimination (the frontend's pre-11.5
workaround for `MoveEvent` vs `GameAbandonedEvent` on the same topic)
keeps working until the frontend migrates to `switch (event.type)`.

**`MOVE`** — published from `GameService.applyMove` after every
accepted move. Carries the post-move state of the game plus the
identifying tuple of which side moved where.

```json
{
  "type": "MOVE",
  "gameId": "550e8400-e29b-41d4-a716-446655440000",
  "movedBy": "8f14e45f-ceea-467a-9575-d4b9b3e8b3a3",
  "side": "WHITE",
  "from": "e2",
  "to": "e4",
  "promotion": null,
  "fen": "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1",
  "status": "ONGOING",
  "turn": "BLACK",
  "moveNumber": 1,
  "playedAt": "2026-05-19T10:23:11.123Z"
}
```

- **`movedBy`** — the player id from `X-Player-Id` on the REST
  request. Lets a client filter out its own moves; the REST
  response already carried the new state.
- **`side`** — `WHITE` or `BLACK`, the side that played this
  move. Convenience for the client.
- **`from` / `to`** — origin / destination squares in lowercase
  algebraic notation. Same alphabet as the REST `MoveRequest`.
- **`promotion`** — `"KNIGHT"`, `"BISHOP"`, `"ROOK"`, `"QUEEN"`,
  or `null` for non-promotion moves.
- **`fen`** — the resulting position after the move, in FEN.
- **`status`** — the resulting `GameStatus`: `ONGOING`, `CHECK`,
  `CHECKMATE`, `STALEMATE`, `DRAW`, or `ABANDONED`.
- **`turn`** — the side whose turn it is now (the inverse of
  `side`). Convenience for the client.
- **`moveNumber`** — the 1-based count of half-moves played in
  the game after this move. A gap in the sequence on the
  subscriber side indicates a missed event and triggers a re-sync.
- **`playedAt`** — ISO-8601 instant in UTC, from the service's
  injected `Clock`.

**`GAME_ABANDONED`** — published from `GameAbandonService.abandon`
when the grace timer fires on a non-terminal game. Terminal
broadcast; no follow-up on this topic.

```json
{
  "type": "GAME_ABANDONED",
  "gameId": "0d52a8a0-bea0-4b21-bbe3-3df7f8e83bfb",
  "abandonedBy": "8f14e45f-ceea-467a-9575-d4b9b3e8b3a3",
  "winnerId": "550e8400-e29b-41d4-a716-446655440000",
  "finalFen": "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1",
  "abandonedAt": "2026-05-22T10:23:11.123Z"
}
```

- **`abandonedBy`** — the player whose session dropped and never
  came back; the loser.
- **`winnerId`** — derived server-side so the frontend does not
  need a second lookup.
- **`finalFen`** — the position frozen on the board at the moment
  of abandonment; the game is terminal and the FEN does not change.
- **`abandonedAt`** — ISO-8601 instant in UTC, from the service's
  injected `Clock`.

**`PLAYER_DISCONNECTED`** — published from
`PlayerSessionTracker.onDisconnect` when a player session drops on
a non-terminal game (the same condition that starts the grace
timer). Opens the mid-grace UX window — the opponent's UI renders
"<player> is reconnecting, <countdown>".

```json
{
  "type": "PLAYER_DISCONNECTED",
  "gameId": "0d52a8a0-bea0-4b21-bbe3-3df7f8e83bfb",
  "playerId": "8f14e45f-ceea-467a-9575-d4b9b3e8b3a3",
  "side": "WHITE",
  "disconnectedAt": "2026-05-22T10:23:11.123Z",
  "gracePeriodEndsAt": "2026-05-22T10:24:11.123Z"
}
```

- **`playerId`** — the player whose STOMP session dropped.
- **`side`** — `WHITE` or `BLACK`, derived server-side so the
  frontend can flag the right player slot.
- **`disconnectedAt`** — ISO-8601 instant in UTC, when the
  disconnect handler observed the session drop.
- **`gracePeriodEndsAt`** — ISO-8601 instant in UTC, the absolute
  deadline at which `GAME_ABANDONED` will fire if the player has
  not reconnected. Computed as `disconnectedAt +
  chess.disconnect.grace-period`. The frontend computes
  `remaining = gracePeriodEndsAt - now()` locally for the countdown
  — absolute deadlines beat `secondsRemaining` integers because the
  value never goes stale on the wire.

**`PLAYER_RECONNECTED`** — published from
`PlayerSessionTracker.onSubscribe` when a subscribe with a
`playerId` header that matches white or black of the game actually
cancels a pending grace timer (i.e. the player reconnected within
the grace window). Closes the mid-grace UX window.

```json
{
  "type": "PLAYER_RECONNECTED",
  "gameId": "0d52a8a0-bea0-4b21-bbe3-3df7f8e83bfb",
  "playerId": "8f14e45f-ceea-467a-9575-d4b9b3e8b3a3",
  "side": "WHITE",
  "reconnectedAt": "2026-05-22T10:23:25.456Z"
}
```

- **`playerId`** — the player whose STOMP session was restored.
- **`side`** — `WHITE` or `BLACK`, same derivation as on
  `PLAYER_DISCONNECTED`.
- **`reconnectedAt`** — ISO-8601 instant in UTC, when the subscribe
  handler observed the reconnect and cancelled the pending timer.

The broadcast is guarded by the boolean return of
`GracePeriodManager.cancelGracePeriod`: emitted only when the cancel
actually removed a pending timer. A fresh subscribe with no prior
disconnect, or a reconnect that arrives after the timer has already
fired (the timer-just-fired race) both produce no broadcast — the
`GAME_ABANDONED` event is the authoritative outcome in the latter
case.

### No STOMP-level authentication

STOMP `CONNECT` does not carry credentials. Anyone who knows a
`gameId` can subscribe to its topic and observe the live move
stream. This is a **deliberate** choice for the portfolio scope:

- It mirrors the REST `GET /api/games/{id}` design, which is also
  unauthenticated.
- It is the foundation that **feature 6.5 (spectator mode)**
  builds on — a spectator is just any party that subscribed to the
  topic without being one of the two players.
- A real product would lock both surfaces behind the same auth
  layer (OAuth, session cookies, JWT — pick one). We have not
  introduced that layer; when we do, both surfaces gain it
  together.

The mutation surface (REST `POST /api/games/{id}/moves`) is
already gated on `X-Player-Id` matching the side to move — a
subscriber who is not one of the two players cannot inject moves
into the game, only observe them.

### Failure mode

Broadcasts are **fire-and-forget**. After `GameStore.compute`
returns successfully and the mutation is persisted, the service
attempts `SimpMessagingTemplate.convertAndSend("/topic/games/" +
gameId, event)`. If the call throws a `RuntimeException` (broker
misconfigured, serialization fails, etc.), the service logs at
`WARN` with the `gameId` and the exception message, and does not
rethrow. Specifically:

- The REST POST still returns 200 with the updated state — the
  client that submitted the move has authoritative confirmation
  via REST, and broadcast loss does not break the originator.
- Subscribers may miss this update. Recovery is a client concern
  handled by the standard pattern: on STOMP disconnect or
  inconsistency, re-fetch state via
  `GET /api/games/{id}` and resume on the topic.

The broadcast happens **outside** the `compute` lambda. A failing
broadcast inside the lambda would propagate out of `compute` and
look like a failed mutation, which it is not — the state is
already committed. This is the same separation-of-concerns
principle that keeps controllers free of try/catch.

### Ordering and concurrency

`ConcurrentHashMap.compute` serializes concurrent moves on the
same `gameId`, so the broadcast for move N completes (or fails)
before the broadcast for move N+1 begins. Subscribers see moves
in the order they were applied. Broadcasts for **different**
games run in parallel; `SimpMessagingTemplate.convertAndSend` is
thread-safe.

### Viewer count broadcasts

Feature 6.5 (spectator mode) layers a second broadcast onto the
same `/ws` endpoint. Every time the set of sessions subscribed to
`/topic/games/{gameId}` changes — a new subscribe, an explicit
unsubscribe, or a session disconnect — the server publishes a
`ViewerCountEvent` to **`/topic/games/{gameId}/viewers`**:

```json
{
  "gameId": "0d52a8a0-bea0-4b21-bbe3-3df7f8e83bfb",
  "count": 3
}
```

- **`gameId`** — the game the count is for; matches the
  `{gameId}` segment in the topic. Clients may subscribe to
  several `/viewers` topics in parallel and use this field to
  demultiplex.
- **`count`** — the current number of subscribers to
  `/topic/games/{gameId}` that are **not** players of the game
  (see the `playerId` convention below).

The viewer-count topic exists separately from the game topic so
that count updates are decoupled from move cadence: a game with
no moves but lots of joiners and leavers still produces a stream
of count changes. The two topics are independent — clients can
subscribe to one, the other, or both.

The viewer-count broadcast is fire-and-forget on the same
failure-mode policy as `MoveEvent`: any `RuntimeException` from
the broker is logged at `WARN` and not rethrown.

### `playerId` header convention

Clients that are one of the two players of a game declare it by
sending their player id as a native STOMP header on the
`SUBSCRIBE` frame to `/topic/games/{gameId}`:

```
SUBSCRIBE
id:sub-0
destination:/topic/games/<gameId>
playerId:<playerId-uuid>

^@
```

The server compares the header value against `white.id()` and
`black.id()` of the game. A match means "this subscriber is a
player, not a spectator" and they are excluded from the viewer
count for that game. No header (or no match) means "this
subscriber is a spectator" and they are counted.

**Trust model:** the server takes the header at face value.
There is no authentication today, so the claim cannot be
verified — a malicious client could omit the header to inflate
the count, or forge another player's id to exclude itself. This
is a **deliberate portfolio-level trade-off**, consistent with
the rest of the no-auth design described above. A future auth
feature would replace "trust" with "verify" without changing the
header name or its semantics; only the validation step on the
server changes.

### Cross-repo coordination

The `chess-frontend` repo mirrors this section in its own
`docs/architecture.md` when it reaches feature 5
(`stomp-live-updates`). Until then, the contract above is the
single source of truth. Changes here must be reflected in the
frontend doc the next time the two are touched. With feature 6.5
in place, the frontend's feature 5 should additionally:

- Subscribe to `/topic/games/{gameId}/viewers` to render the
  live spectator count.
- Optionally send the `playerId` native header on the
  `SUBSCRIBE` to `/topic/games/{gameId}` when the current user
  is one of the two players of the game.

## Source of truth

The **server** is the source of truth for game state. The client can
display a board, but the server validates every move. This is the key
behavioral difference from the Node version, which trusted the client.

A move is only accepted if:

1. The game exists and is `ONGOING`.
2. The requester is one of the two players in the game.
3. It is that player's turn.
4. The move is legal according to chesslib given the current position.

If any of these fail, the server rejects the move with `422 Unprocessable
Entity` and the client must reconcile its state with the server's.

## State strategy

Two stores with clear responsibilities:

**Redis — active state, ephemeral.**

- Active rooms (waiting for second player or with an in-progress game).
- Active game state (current FEN, turn, status, players' connection
  state).
- TTL on every key (e.g., 24 hours). Activity refreshes the TTL.
- If Redis is wiped, in-flight games are lost. That is acceptable for
  a portfolio chess app.

**Postgres — durable history.**

- Completed games (result, full move history, players, timestamps).
- Player records (just an ID and a display name for now; no auth).
- Schema managed by Flyway under `src/main/resources/db/migration/`.

A game lives in Redis while it is active. When it completes (checkmate,
draw, resignation, abandonment), it is written to Postgres and removed
from Redis.

### Active state in Redis

Feature 8 swaps the day-one in-memory stores
(`InMemoryRoomStore` / `InMemoryGameStore`) for Redis-backed
implementations (`RedisRoomStore` / `RedisGameStore`) behind the
unchanged `RoomStore` / `GameStore` seam. The service, controller, and
WebSocket layers are not aware of the swap.

- **Key schema.** Flat namespaces: `room:{id}` and `game:{id}`. Values
  are JSON serialized by per-type
  `Jackson2JsonRedisSerializer<T>` so `redis-cli GET room:ABC123`
  returns inspectable JSON without `@class` type metadata.
- **TTL.** Every `save` calls `opsForValue().set(key, value, ttl)` with
  `ttl` taken from `chess.redis.active-state-ttl` (default `24h`, bound
  through `@ConfigurationProperties` as a `Duration`). Reads do not
  refresh; only mutations do. An abandoned room or game disappears
  after the configured lease elapses with no extra background job.
- **Atomicity.** The `compute(id, fn)` contract is preserved via a
  process-local `StripedKeyLock` (`ConcurrentHashMap<String,
  ReentrantLock>`): two callers on the same key serialize on the same
  lock, different keys proceed in parallel. This is sufficient because
  the production deployment is single-instance (see
  "Deployment artifact" above). Moving to multi-instance would replace
  this lock with a Redis-side mechanism (`WATCH/MULTI/EXEC`, Redlock,
  or a Lua script) behind the same `RoomStore` / `GameStore` interface
  — no consumer change required.
- **Cross-store invariant.** "A game exists iff its room is `ACTIVE`"
  still lives where it did before, inside `RoomService.joinRoom`: the
  `gameStore.save(game)` runs inside the `roomStore.compute(roomId,
  ...)` block, so the two writes happen sequentially while the
  room-level lock is held. A JVM crash between the two writes is the
  same worst case as before (a room marked `ACTIVE` with no game) and
  will be formalised by feature 10 (`disconnect-handling`).
- **Out of scope.** The viewer-count tracker (feature 6.5,
  `ViewerCountTracker`) stays in-memory: it is bound to STOMP sessions
  on this server, which already cannot survive a restart. Moving it to
  Redis would be misleading.

### Game history in Postgres

Feature 9 layers a durable archive on top of the Redis active store.
Redis still owns ongoing games (the 24h-TTL `game:{id}` keys
described above); Postgres owns the read-only history of games that
already finished.

- **Write trigger.** `GameService.applyMove` calls
  `GameHistoryService.archive(updated)` from inside the
  `GameStore.compute` lambda whenever `updated.status().isTerminal()`
  flips true (`CHECKMATE`, `STALEMATE`, `DRAW`, `ABANDONED`). The
  archive runs **before** the lambda returns, so if Postgres throws
  the Redis-side write is skipped: the move request fails with 500
  and Redis still holds the previous (non-terminal) state. The
  inverse ordering — Redis advances, archive silently fails — would
  produce a ghost terminal game observable in no history query;
  failing loud is the safer default for a portfolio backend.
- **Schema.** Two tables, both managed by Flyway under
  `src/main/resources/db/migration/`:
    - `games` — one row per archived game with denormalised player
      info (`white_player_id`, `white_display_name`,
      `black_player_id`, `black_display_name`), `starting_fen`,
      `final_fen`, `status` (`VARCHAR(20)`, JPA-enforced enum), and
      `ended_at` (`TIMESTAMPTZ`). `id`, `white_player_id`, and
      `black_player_id` are native Postgres `uuid`; `room_id` is
      `VARCHAR(6)` (the 6-char short code is not a UUID).
    - `moves` — N rows per game, PK `(game_id, move_idx)`, FK
      cascade-deletes from `games`, `from_square` / `to_square` as
      `VARCHAR(2)` / nullable `promotion` as `VARCHAR(10)` (the
      piece's enum `name()` or NULL).
  Two indexes on `games (white_player_id, ended_at DESC)` and
  `(black_player_id, ended_at DESC)` make the history query a
  single indexed scan.
- **UUID end-to-end.** Every column that is conceptually a UUID is a
  native Postgres `uuid` on the DB side, a `java.util.UUID` on the
  JPA entity, and a `java.util.UUID` on the domain record. Spring
  binds path variables and headers to `UUID` directly via its
  default `String→UUID` converter, and Jackson serialises `UUID` to
  a plain JSON string — so the wire format is identical to the
  previous TEXT-backed version, no frontend coordination required.
  No `@JdbcTypeCode(SqlTypes.UUID)` trick is needed; Hibernate has
  the built-in `UUID ↔ uuid` binding. A malformed UUID in a path
  surfaces as `MethodArgumentTypeMismatchException` → `400
  MALFORMED_REQUEST` via the global handler.
- **Bounded `VARCHAR(N)` with `ddl-auto: validate`.** Every text
  column has an explicit length on both sides (`@Column(length =
  N)` and `VARCHAR(N)`), and `validate` checks the length code on
  startup — a drift between the entity's bound and the column's
  bound fails boot loudly. `CHAR(2)` was the first choice for
  squares; rejected because Hibernate maps Java `String` to
  `Types.VARCHAR` and Postgres reports `CHAR(N)` as `Types.CHAR`,
  which the `validate` codes-match check refuses. `VARCHAR(2)` has
  the identical storage footprint for short fixed-width content.
- **No `players` table — snapshot semantics.** Guests have no other
  attached data, so normalising the player id would buy no benefit
  and would force a join on every history query. Beyond that, the
  display name at archive time is the audit-correct value (same
  shape as Lichess game records, GitHub commit author names, Steam
  friend graph history): a future rename must not retroactively
  rewrite past games. Denormalising both fields onto `games` is
  the right call for both reasons. When auth landed in feature 16,
  the User-to-Game link was added as two nullable FK columns
  directly on `games` (`white_user_id`, `black_user_id`) rather
  than an intermediate `players` table — preserving the same
  denormalised shape V1 codified. See the "Authentication" section
  above for the full rationale.
- **No Postgres ENUM type for `status` / `promotion`.** `VARCHAR`
  with JPA's `@Enumerated(STRING)` enforces the alphabet on the
  only writer of the DB. A Postgres ENUM would require a one-way
  `ALTER TYPE ... ADD VALUE` on every additional `GameStatus`
  constant and per-column annotations on the entity to bridge the
  Hibernate-to-ENUM mapping. The canonical Spring/JPA pattern is
  `VARCHAR + @Enumerated(STRING)`, used by Lichess, GitHub, and the
  official Spring Data JPA examples.
- **No `CHECK` constraint on `status`.** Same reasoning: JPA already
  enforces the alphabet on the write path. A DB check would have
  to be DROP + ADD on every future `GameStatus` addition; trusting
  the entity is the simpler trade-off.
- **Move notation: LAN, not SAN.** A `Move` is `(from, to,
  Optional<Piece> promotion)`. The from/to pair is unambiguous;
  promotion is the only intra-move ambiguity (a pawn can promote
  to one of four pieces), correctly modelled as `Optional<Piece>`.
  Check, checkmate, and stalemate are post-move *game state*, not
  properties of a move — encoding them inside `Move` would
  denormalise the game state. SAN is a presentation concern,
  derivable from LAN + board on demand.
- **JPA entity vs domain record.** The domain `Game` is an
  immutable record. JPA needs a mutable class with a no-arg
  constructor and writable fields, so the persistence layer
  defines a parallel `GameEntity` / `MoveEntity` shape with
  package-private setters and a `GameEntityMapper` (`@Component`)
  as the only ingress/egress. Outside the `persistence` package no
  code holds a writable reference to the entity.
- **`ddl-auto: validate` + Flyway.** Flyway is the source of truth
  for the schema; Hibernate's `ddl-auto: validate` verifies the
  JPA model against the actual database at boot. If an entity gains
  a column the migration forgets, the app refuses to start. The
  seductive `update` default is the silent-drift footgun we
  deliberately avoid.
- **`spring.jpa.open-in-view: false`.** The Spring Boot default
  keeps the JPA session open for the entire HTTP request, which
  masks lazy-loading bugs in the view layer. Off here means every
  access to a lazy association outside a `@Transactional` service
  method fails loudly. The history endpoint sidesteps this entirely
  by using a JPQL constructor projection
  (`ArchivedGamePlayerView`) that pre-computes `SIZE(g.moves)` in
  the same SQL round-trip — no lazy navigation outside the
  service's transaction.
- **History endpoint.** `GET /api/players/{id}/games` returns a
  newest-first list of `PlayerGameSummary` records, hard-capped at
  50 entries. No pagination param exposed (portfolio scope). An
  unknown player id returns `200` with `[]` — guests have no
  registry, and an empty list is the honest answer (no `404`).
- **Out of scope.** A game-detail endpoint (replay viewer with all
  moves) is a follow-up feature; the `moves` table already carries
  the data, the endpoint will project a different shape.
  Pagination, player accounts, and an explicit
  delete-from-Redis-on-archive step are similarly deferred.

## Communication patterns

**REST for actions.** Anything that changes state goes through a REST
endpoint. This makes the API explicit, easy to test with `curl` or
HTTP integration tests, and easy to document.

**STOMP for broadcasts.** When a move is accepted, the server publishes
the new state on `/topic/games/{id}`. Subscribers (the opponent, and
optionally spectators) receive it.

The client therefore has two parallel channels:

- It **sends** moves over REST.
- It **receives** state updates over STOMP.

This avoids the complexity of bidirectional WebSocket message protocols
and keeps actions auditable.

## Reconnection

Feature 11 (`disconnect-handling`) implements the lifecycle correctness
layer described here. The mid-grace UX polish (`PlayerDisconnectedEvent`
opens the "<player> is reconnecting, <countdown>" banner;
`PlayerReconnectedEvent` clears it; the absolute `gracePeriodEndsAt`
deadline drives the local countdown) shipped in feature 11.5
(`disconnect-notifications`).

When a STOMP session disconnects, the player enters a grace period
(60 seconds by default, configurable via
`chess.disconnect.grace-period`). If they resubscribe to the same
`/topic/games/{gameId}` with the same `playerId` native header within
the window, the timer is cancelled and the game continues unchanged.
If the timer fires, the game is marked `ABANDONED` with the opponent
as the winner.

Reconnection works because state is on the server, not in the browser.
A client that reloads the page can re-fetch the game state via
`GET /api/games/{id}` and re-subscribe to the STOMP topic.

### Disconnect handling

The mechanism is built on four pieces wired in series:

- **`PlayerSessionTracker`** (`websocket/`). A second
  `@EventListener` consumer of Spring's STOMP session events, parallel
  to `ViewerCountTracker` but tracking the two players of a game
  rather than spectators. It reuses the same `playerId` native-header
  convention (documented in "STOMP API contract → `playerId` header
  convention"): on a `SessionSubscribeEvent` for
  `/topic/games/{gameId}` whose `playerId` matches white or black of
  the game, the tracker records the `(sessionId → (playerId, gameId))`
  association, calls `GracePeriodManager.cancelGracePeriod` to drop
  any pending timer for the pair, and — if the cancel actually
  removed a timer — broadcasts `PlayerReconnectedEvent` on
  `/topic/games/{gameId}`. On a `SessionDisconnectEvent` the
  association is removed and, if the game is still non-terminal,
  `GracePeriodManager.startGracePeriod` is invoked and a
  `PlayerDisconnectedEvent` is broadcast on the same topic carrying
  the absolute `gracePeriodEndsAt` deadline. The two trackers are
  orthogonal: a session is in one map or the other, never both.
- **`GracePeriodManager`** (`service/`). The single owner of the
  in-memory `Map<(playerId, gameId), ScheduledFuture<?>>` of pending
  abandon timers. Uses a programmatic `TaskScheduler.schedule(task,
  Instant)` (configured in `SchedulingConfig`) — not `@Scheduled` —
  because the timers are one-shot, addressable, and cancellable on
  reconnect, none of which the declarative annotation supports. A
  fresh `StripedKeyLock` instance (the same primitive
  `RedisGameStore` uses for per-key atomicity, repurposed for a new
  domain) serializes `start` / `cancel` / `fire` on the same key, so
  a reconnect that arrives during a fire cannot race the abandon
  path's `active.remove` with its own cancel.
- **`GameAbandonService`** (`service/`). The terminal-by-timeout
  counterpart of `GameService.applyMove`'s terminal-by-move path.
  Flips the game to `ABANDONED` inside a `gameStore.compute` block
  (atomic per `gameId`), then archives via
  `GameHistoryService.archive` and broadcasts a `GameAbandonedEvent`
  on `/topic/games/{gameId}`. Idempotent: a no-op when the game is
  already terminal or does not exist.
- **`SchedulingConfig`** (`config/`). Brings up
  `@EnableScheduling` and registers a `ThreadPoolTaskScheduler` bean
  (pool size 2, daemon threads, `chess-scheduler-` thread name
  prefix). Also exposes `DisconnectProperties` via
  `@EnableConfigurationProperties`, mirroring the `CorsConfig` /
  `CorsProperties` and `RedisConfig` / `RedisActiveStateProperties`
  patterns from features 10 and 8.

**Archive trigger lives in two places.**
`GameService.applyMove` calls `GameHistoryService.archive(...)`
inside its `gameStore.compute` lambda when a move flips the game
into a terminal status; `GameAbandonService.abandon` calls it
after its own `gameStore.compute` block when the abandon timer
fires. We deliberately did **not** extract a shared helper:
the two call sites have different surrounding context (move
applied vs status mutated), and a shared helper would obscure
that. Both sites remain explicit and short.

**Server restart limitation.** The pending-timers map is
process-local. A JVM restart with grace timers in flight loses
them; affected games stay in their current non-terminal status
until something else terminates them (a move, or a fresh
disconnect that schedules a new timer). Persisting timers to
Redis with a restart-time recovery sweep would be heavyweight
for the single-instance deploy and is left for a future feature.

**Events on `/topic/games/{gameId}` after feature 11.5.** The topic
now carries four discriminated variants of the `GameStateEvent`
sealed family (`MOVE`, `GAME_ABANDONED`, `PLAYER_DISCONNECTED`,
`PLAYER_RECONNECTED`), each with an explicit `type` field set by
its convenience constructor. The full shapes are documented in the
"STOMP API contract → `GameStateEvent` family" section above. The
disconnect lifecycle now emits up to three events per session
drop: `PLAYER_DISCONNECTED` at drop time (with the absolute
`gracePeriodEndsAt`), then either `PLAYER_RECONNECTED` (if the
reconnect lands within the grace window and cancels the timer) or
`GAME_ABANDONED` (if the timer fires). Spring's `SimpleBroker`
preserves topic order from a single publisher process, so
subscribers see `PLAYER_DISCONNECTED` strictly before whichever
follow-up arrives.

**Trust model and out-of-scope items** mirror the broader
no-auth posture documented in the STOMP contract section above:
the `playerId` header is taken at face value; spectator
disconnects are handled separately by `ViewerCountTracker` and
do not enter the abandon path; rate-limiting reconnect attempts
is not added since the security posture has no anti-abuse layer
yet.

## Error handling

A small exception hierarchy lives in `exception/`. Examples:

- `RoomNotFoundException` → `404`.
- `RoomFullException` → `409`.
- `IllegalMoveException` → `422`.
- `NotYourTurnException` → `422`.

A single `@RestControllerAdvice` maps each exception to a structured JSON
body: `{ "error": "ROOM_NOT_FOUND", "message": "..." }`. Controllers do
not catch their own exceptions.

## Testing strategy

Two flavors of test:

- **Unit tests** for domain logic (chess rules wrapper, simple service
  logic that has no Spring dependencies). Plain JUnit 5. Fast. Live
  alongside production code in `src/test/java/...`.
- **Integration tests** with the `IT` suffix. Boot a Spring context,
  use Testcontainers for real Postgres and Redis, exercise the system
  end to end (REST + STOMP). These are slower but they are the ones
  that prove the system works.

No H2. No in-memory Redis fakes. Real services in tests, always.

## Observability

The project ships with the basics. We are not building a full
observability stack, but a portfolio backend should not be a black box.

- **Structured logs** (JSON) via Logback. Each request gets a
  correlation ID propagated through the call.
- **Spring Boot Actuator** for `/api/health` and `/api/info`.
- Metrics will be added if we have time. Micrometer + Prometheus is the
  obvious path.

## What is intentionally out of scope

- **Authentication.** Players are identified by an opaque ID set at
  room creation. A real product would have OAuth or similar.
- **Ratings (ELO).** The data model leaves room for it; we do not
  implement it.
- **Spectators.** Topic subscription would allow them, but we do not
  build the UX for it.
- **Tournament structure.** One game at a time per room.
- **Move clocks / time controls.** Possible future feature.

Document these out-of-scope items in the README so reviewers know they
were considered and deferred, not forgotten.
