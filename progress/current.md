# Current session — auth bundle

**Status:** in_progress on feature 16 (`auth-core`).
**Opened:** 2026-05-27.
**Scope:** add optional authentication. Guest play stays open; an
account unlocks "see my past games" (and lays the groundwork for
future per-user features).

`feature_list.json` snapshot: **24 done, 1 in_progress, 4 pending.**
(The historical "23 done" count in earlier `current.md` versions and
in memory was an undercount — the actual length of `feature_list.json`
before this session was 24 entries. Memory updated at session close.)

---

## Why this bundle

User goal (verbatim 2026-05-27): *"seria opcional, se puede seguir
juegando sin cuenta, pero con una cuenta se pueden revisar las
partidas jugadas por ejemplo"*.

The auth surface is the single highest-signal addition we can ship to
a senior backend portfolio: it touches Spring Security, JPA, CORS,
JWT cryptography, OAuth2 client integration, and STOMP message
interception — concepts a recruiter expects to see exercised on a
backend with a real frontend and a real production deploy.

---

## Bundle decomposition (features 16–20)

| Priority | ID | One-line goal |
| --- | --- | --- |
| 16 | `auth-core` | User entity, Flyway migration, Spring Security base, JWT validation, `GET /api/me`. No issuance yet. |
| 17 | `auth-jwt` | Email/password register + login → JWT (HS256). The JWT shape is locked here. |
| 18 | `auth-google-oauth` | Google OAuth 2.0 client; success handler redirects to frontend with JWT in URL fragment. |
| 19 | `auth-my-games` | `GET /api/me/games` (paginated). Authenticated game creation links `Player.user_id`. |
| 20 | `auth-stomp-trust` | STOMP `ChannelInterceptor` validates JWT on CONNECT and prevents identity spoofing on SEND/SUBSCRIBE. |

Out-of-scope for the whole bundle (decided 2026-05-27 with user):

- Refresh tokens (single JWT, 7-day expiry).
- Email verification / password reset / magic links.
- 2FA / TOTP.
- Account linking (Google + email/password in the same User).
- Claim flow for pre-existing anonymous games (fresh-start identity
  model: anonymous games created before login stay anonymous forever).

---

## Bundle-level technical decisions

These are answered once at the bundle level so each feature plan can
stay narrow:

1. **Token transport:** stateless JWT in `Authorization: Bearer <token>`
   header. No session cookies. Confirmed with user 2026-05-27.
2. **JWT algorithm:** HS256 with a shared secret in env var
   `AUTH_JWT_SECRET`. Symmetric is fine because issuer = verifier
   (same backend). Token lifetime: 7 days.
3. **JWT claims:** `sub` = `User.id` (UUID string), `email`,
   `iat`, `exp`. No roles / authorities yet (single role implicit:
   "authenticated user").
4. **OAuth callback delivery:** backend redirect to
   `${auth.oauth.frontend-redirect-base}/auth/callback#token=<jwt>`.
   Frontend reads fragment, stores token, clears with
   `history.replaceState`. Confirmed with user 2026-05-27.
5. **CORS:** `allowCredentials` stays **false**. The new
   `Authorization` header is already on the allow-list since feature
   11.7 (`cors-x-player-id`) — adding `Authorization` to the
   `allowed-headers` list is a free pre-existing benefit. Reviewer of
   feature 16 confirms via a Bearer-header preflight IT.
6. **Identity linking:** fresh start. `User.id` is the new canonical
   identity; the link to games is **two nullable FK columns on the
   `games` table itself** — `white_user_id` and `black_user_id`,
   each FK to `users(id)`. No intermediate `players` table.
   Rationale: V1's `games` table is deliberately denormalised
   (`white_player_id` + `white_display_name` + `black_player_id` +
   `black_display_name`) precisely because adding a `players` row
   would only duplicate the UUID + display name with no extra
   attached data. The cleanest auth integration is to keep that
   shape and add user FKs alongside the existing player snapshots.
   The historical `games.white_player_id` / `black_player_id`
   columns stay; they are the audit-snapshot identity at game-time
   and remain unconstrained UUIDs (no FK). Anonymous games forever
   keep `white_user_id` = `black_user_id` = null. Confirmed with
   user 2026-05-27 after the implementer's first pass surfaced the
   alternative.
7. **STOMP auth surface:** anonymous STOMP keeps working (guest
   play); JWT only used to **strengthen** identity, never to gate
   access. Identity spoofing is blocked starting in feature 20.

These decisions live here, not in every feature plan, so reviewers can
cross-reference one source.

---

## Feature 16 — `auth-core` — detailed plan

### Approach

This feature lands the foundation without any user-facing auth flow
yet. JWT *validation* exists; JWT *issuance* arrives in feature 17.
This split keeps the diff reviewable and lets `auth-core` ship with
its own tests (using a pre-generated test JWT) before issuance lands.

The `games.white_user_id` and `games.black_user_id` FK columns are
added now (not in feature 19) so the data model is complete from the
start of the bundle. Both columns stay null for every existing row
and every anonymous game created during features 16–18; feature 19
starts populating them for authenticated game creation. No
intermediate `players` table — see bundle decision 6 above for the
rationale.

### Files created or modified, by package

**`io.github.dariogguillen.chess.domain`** (new entity)
- `User.java` — new. JPA entity (not a record — needs a no-args
  constructor for JPA). Fields: UUID id, String email (unique
  index), String displayName, String passwordHash (nullable), String
  googleSub (nullable, unique index where not null), Instant createdAt.

**`io.github.dariogguillen.chess.persistence`** (new repo only)
- `UserRepository.java` — new. `extends JpaRepository<User, UUID>`
  with `findByEmail(String)` and `findByGoogleSub(String)`.
- **No `Player.java` change.** `Player` stays a pure domain record;
  there is no JPA entity for `players` in this repo. The User↔Game
  link lives on the `games` table (see migration below) instead of
  on a `players` table that does not exist.

**`io.github.dariogguillen.chess.config`** (new + modified)
- `SecurityConfig.java` — new. `SecurityFilterChain` bean: stateless
  (`SessionCreationPolicy.STATELESS`), CSRF disabled (JWT in header
  is not vulnerable to CSRF), CORS delegates to existing
  `CorsConfig`. `BCryptPasswordEncoder` bean (unused this feature
  but needed by feature 17 — exposed now to keep beans wired).
  Authorization rules: anonymous allow-list (see below); everything
  else `.authenticated()`.
- `AuthProperties.java` — new. `@ConfigurationProperties("auth.jwt")`
  record: `String secret`, `long expirySeconds`.
- `CorsProperties.java` — **modified** to ensure `Authorization` is
  in `allowed-headers`. The existing list already has `Content-Type,
  Accept, X-Player-Id` since feature 11.7; this feature adds
  `Authorization`. (Plan note: if it's already there, the change is
  a no-op.)

**`io.github.dariogguillen.chess.config.security`** (new package)
- `JwtAuthenticationFilter.java` — new. Extends
  `OncePerRequestFilter`. Reads `Authorization: Bearer ...`, decodes
  with `JwtVerifier`, loads `User` by `sub` claim, sets a
  `UsernamePasswordAuthenticationToken` on `SecurityContextHolder`.
  No exceptions thrown on missing/invalid token — chain continues
  anonymous; the auth requirement is enforced by
  `SecurityFilterChain` rules.
- `JwtVerifier.java` — new. Wraps the JWT library (jjwt-api +
  jjwt-impl + jjwt-jackson). Method `Claims verify(String token)`
  throws on expiry/bad signature/malformed. Used in feature 17 as
  well, exposed as a Spring bean.

**`io.github.dariogguillen.chess.web.auth`** (new package)
- `MeController.java` — new. `@GetMapping("/api/me")` returns a
  `MeResponse` from the `SecurityContext`'s principal. If the
  filter chain reaches here, the user is authenticated by
  construction; otherwise the chain returns 401 before this method
  runs.
- `MeResponse.java` — new. Record: UUID id, String email, String
  displayName.

**`src/main/resources/db/migration`** (new)
- `V2__create_users_and_game_user_links.sql` — new. Creates
  `users` table (id UUID PK, email TEXT NOT NULL UNIQUE,
  display_name TEXT NOT NULL, password_hash TEXT, google_sub TEXT,
  created_at TIMESTAMPTZ NOT NULL); adds partial unique index on
  `google_sub WHERE google_sub IS NOT NULL`. Adds two nullable FK
  columns to the existing `games` table:
  `white_user_id UUID NULL REFERENCES users(id)` and
  `black_user_id UUID NULL REFERENCES users(id)`, each with a
  partial index `WHERE *_user_id IS NOT NULL` so guest games do
  not bloat the indexes. **Does NOT create a `players` table.**

**`src/main/resources`** (modified)
- `application.yml` — adds `auth.jwt.secret: ${AUTH_JWT_SECRET}`
  with NO default (fail-fast at boot if env var missing in prod);
  `auth.jwt.expiry-seconds: 604800`.
- `application-test.yml` (or test-profile section) — provides a
  fixed `auth.jwt.secret` long enough for HS256 (≥ 32 bytes).

**`pom.xml`** (modified)
- Adds jjwt dependencies (api, impl, jackson). NO new Spring
  starter — `spring-security-config` and `spring-security-web`
  are pulled transitively by adding
  `spring-boot-starter-security` (which we add here for the first
  time).

**`src/test/java/.../config/security`** (new IT)
- `AuthCoreIT.java` — new. Cases:
  1. `me_withoutAuthHeader_returns401`.
  2. `me_withValidJwt_returns200WithUserPayload`.
  3. `me_withExpiredJwt_returns401`.
  4. `me_withMalformedJwt_returns401`.
  5. `me_withWrongSignature_returns401`.

**`src/test/java/.../config`** (modified or new IT)
- `BearerCorsIT.java` — new. Single test confirming a preflight
  with `Access-Control-Request-Headers: Authorization` returns the
  header in `Access-Control-Allow-Headers`. Pins feature 16's CORS
  promise.

**`src/test/java/.../web/game`** (regression)
- Confirm existing `GameIT` / `RoomIT` anonymous flows stay green
  without modification. No code changes; named here so the reviewer
  knows to verify them explicitly.

**`docs/architecture.md`** (modified)
- New "Authentication" section after the "API contract" section.
  Documents the User aggregate, the bundle scope, the JWT model,
  the fresh-start identity decision, and a note that issuance
  arrives in feature 17.

**`README.md`** — **NOT modified this feature.** Public API still
anonymous-only until feature 17 ships. Reviewer confirms no README
delta.

**`notes/16-auth-core.md`** (new)
- Follows `notes/_template.md`. Cross-ecosystem section covers:
  Typelevel `tsec` for JWT, `pac4j-scala` for OAuth2, http4s
  middleware vs Spring Security filter chain mental model, `jjwt`
  as the Java de-facto vs `pdi/jwt` in Scala.

### Verification

`./init.sh` is the canonical gate. New ITs (`AuthCoreIT`,
`BearerCorsIT`) extend the IT count; unit tests likely unchanged
(security plumbing is exercised at the IT level, not unit). Existing
181 tests must all stay green — explicit smoke target is the
anonymous game creation IT.

Reviewer's extra checks (called out so they're not surprised):
- Flyway migration applied cleanly against the Testcontainers
  Postgres.
- `users` table + `players.user_id` column present after migration.
- `AUTH_JWT_SECRET` absent at boot causes a `BeanCreationException`
  in the prod profile (test profile must provide one).
- `/api/me` is **not** in the anonymous allow-list.

### Cross-repo coordination

**None this feature.** No new endpoint is consumed by the frontend
yet — `/api/me` is unreachable without a JWT, and JWT issuance lands
in feature 17. The frontend's auth work begins coordinated against
feature 17, not this one.

The bundle as a whole eventually requires frontend changes (login
form, OAuth button, token storage, Authorization header on
outgoing requests, callback handler for the OAuth fragment).
Coordination is feature-by-feature on that side; not blocking for
backend 16.

### Java / Spring concepts to highlight in the feature note

- `SecurityFilterChain` bean + `HttpSecurity` DSL (the Spring 6+
  way; the old `WebSecurityConfigurerAdapter` is deprecated).
- `OncePerRequestFilter` as the standard hook point for custom
  auth filters.
- `SessionCreationPolicy.STATELESS` and why it matters for JWT.
- Why CSRF protection is safe to disable for header-based JWT
  (no cookie → no automatic browser attachment → no CSRF vector).
- `@ConfigurationProperties` typed binding (Scala parallel:
  `pureconfig`'s `ConfigReader` derivation).
- Flyway versioning convention; the `V2__` prefix and idempotent
  migrations.
- The principal pattern in Spring Security: `Authentication` +
  `Principal` + how `SecurityContextHolder` flows the value to
  controllers via `@AuthenticationPrincipal`.

---

## Carried over from 2026-05-25 closure (operator follow-ups)

These still apply and are now bundle-adjacent:

- **Rotate the RDS master password.** Especially important before
  shipping auth — credentials should not leak from the chat history
  into a database that will soon hold real user accounts.
- **Configure branch protection on `main`.** The `ci.yml` from
  feature 13 exists but is only advisory. Strongly recommended
  before this auth bundle merges.
- **Replace the static "181 tests" claim in `README.md`** with a
  dynamic count or remove it. The bundle will grow the count.

These are operator actions — not feature 16 acceptance items.

---

## Leader notes for the next handoffs

- Feature 16's implementer handoff is next. Plan needs user
  approval first per `leader.md`.
- When feature 16 closes, this file gets replaced with feature 17's
  detailed plan, but the "Bundle decomposition" + "Bundle-level
  technical decisions" sections above get copied forward verbatim
  so each in-progress plan stands alone.
- Per [[feedback-flag-untracked-files-at-close]]: at every feature
  close in this bundle, explicitly list untracked files (new
  packages like `config/security/`, `web/auth/`, the migration
  file, the new test file). The bundle adds new packages — high
  risk of missing a `git add`.
