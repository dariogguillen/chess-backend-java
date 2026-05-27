# Feature 16 — Authentication foundation (`auth-core`)

**Feature ID:** `auth-core` (from `feature_list.json`)

**Status:** in progress

---

## What we built

The first feature of the auth bundle (16-20). Introduces the `User`
aggregate (UUID id + unique email + display name + nullable BCrypt
password hash + nullable Google `sub` + audit timestamp), the Flyway
V2 migration that creates `users` and adds two nullable FK columns
to the existing `games` table (`white_user_id`, `black_user_id`,
each `UUID NULL REFERENCES users(id)`), Spring Security 6's
stateless filter chain validating `Authorization: Bearer <jwt>`
against the configured HS256 secret, and a single new endpoint `GET
/api/me` that returns the authenticated user or 401. **JWT issuance
is not in scope** — feature 17 owns the register/login endpoints;
this feature only adds the validator side so `/api/me` can be tested
with a pre-generated token. Guest play (`POST /api/games`, `GET
/api/games/{id}`, STOMP `/ws`, etc.) stays anonymous.

There is deliberately **no intermediate `players` table**. The
User-to-Game link is two columns on `games` directly; see the
"Decisions taken" section below for the rationale and the cycle-1
mistake that this design replaces.

## Java / Spring concepts that appear

- **`SecurityFilterChain` bean (Spring Security 6 idiom).** The
  modern replacement for the deprecated `WebSecurityConfigurerAdapter`
  subclass shape. You declare a `@Bean` of type
  `SecurityFilterChain` and configure an injected `HttpSecurity`
  via its DSL — `csrf(...)`, `cors(...)`,
  `sessionManagement(...)`, `authorizeHttpRequests(...)`,
  `addFilterBefore(...)`. The adapter shape was removed in Spring
  Security 6; the bean-and-DSL shape composes cleanly with the
  rest of Spring's bean wiring and is testable in isolation. See
  [Spring Security 6 — Java Config](https://docs.spring.io/spring-security/reference/servlet/configuration/java.html).
- **`OncePerRequestFilter` as the standard hook point for custom
  auth filters.** Lives in `org.springframework.web.filter`, runs
  exactly once per request even under internal forwards, and gives
  you an explicit `doFilterInternal` method to override. The
  Spring Security reference docs recommend extending it for any
  filter that reads a token from the request and writes to the
  `SecurityContext`. We use it for `JwtAuthenticationFilter`,
  inserted before `UsernamePasswordAuthenticationFilter` — the
  canonical placement.
- **`SessionCreationPolicy.STATELESS` and what it implies.** With
  this policy, Spring never creates an `HttpSession` and never
  reads `SecurityContext` from one. Every request's
  `SecurityContext` is built from scratch by our filter (from the
  JWT), used for the duration of the request, and discarded.
  Two structural consequences: (1) horizontal scaling is cheap
  because the server holds no per-request state; (2) CSRF
  protection is safe to disable, because the credential is no
  longer a cookie the browser automatically attaches to
  cross-origin requests — it's a header the script must set
  explicitly.
- **`@ConfigurationProperties` typed binding with a compact-
  constructor invariant.** `AuthProperties` binds `auth.jwt.*` and
  rejects empty/short secrets in its compact constructor. The
  fail-fast happens at bean-binding time: an operator who deploys
  to production without `AUTH_JWT_SECRET` set sees a clear
  `BeanCreationException` on startup rather than a silent
  default-secret footgun. The compact constructor is the
  Java-record equivalent of validating data at construction time
  — same posture as Scala's `case class` with a `require(...)`
  inside.
- **Spring Security's principal pattern.** A populated
  `Authentication` (`UsernamePasswordAuthenticationToken` in our
  case) holds the principal (the `User`), the credentials (`null`
  for token-auth — the credential is the JWT we already
  validated), and the authorities (`List.of()` — no roles yet).
  `SecurityContextHolder.getContext().setAuthentication(...)` is
  thread-local; the principal flows from the filter to the
  controller method's `@AuthenticationPrincipal User` parameter
  via Spring's argument resolver.
- **Flyway versioning convention.** Files are named
  `V{N}__{description}.sql`. Flyway records every applied
  migration in `flyway_schema_history` with a checksum;
  retroactively editing an already-applied V1 fails the next boot
  loudly. Forward-only is the discipline — every change is a new
  Vn+1 file, never an edit to the past. V2 here is purely additive
  (new tables, no schema breakage for existing data).
- **jjwt's `Jwts.parser().verifyWith(key)` API.** The new fluent
  shape introduced in jjwt 0.12.x. The old `Jwts.parserBuilder()`
  + `setSigningKey(...)` form still works but is on its way out.
  `parseSignedClaims(token).getPayload()` returns the decoded
  `Claims` map; bad signature / expired / malformed all throw a
  `JwtException` subclass we catch once and uniformly translate
  to "anonymous chain".

## Decisions taken

**Decision: NO intermediate `players` table. The User-to-Game link
is two nullable FK columns directly on `games`.**

- *What:* `V2__create_users_and_game_user_links.sql` creates the
  `users` table and runs `ALTER TABLE games ADD COLUMN
  white_user_id UUID NULL REFERENCES users(id), ADD COLUMN
  black_user_id UUID NULL REFERENCES users(id)`. Two partial
  indexes (`WHERE *_user_id IS NOT NULL`) keep the populated-only
  path fast without bloating the index for guest games. No
  intermediate table is introduced.
- *Alternatives considered:*
  - **An intermediate `players` table with `(id, display_name,
    user_id FK, kind, created_at)`.** This was the cycle-1
    implementation. It treated `players` as a bridge between the
    existing `games.{white,black}_player_id` ephemeral UUIDs and
    the new `users.id`. Rejected by the user during review. The
    table duplicates the (UUID + display_name) snapshot that V1
    already keeps denormalised on `games`, adds a join on every
    history query, and contradicts V1's deliberate design
    rationale (documented at length in V1's top-of-file comment).
    A future rename-history or claim-flow feature does not
    require a `players` row — the User-to-Game link is what those
    features need, and the FK on `games` provides it directly.
  - **Add the user FK columns AND backfill existing
    `games.*_player_id` UUIDs into a `players` table with
    `kind='HISTORICAL_GUEST'`.** Rejected because backfilling
    sits outside the auth-foundation scope (feature 19's
    territory at the earliest), and the historical player_id
    snapshots are deliberately preserved as audit-time identity
    — they are not user identities, even retroactively.
- *Why this one:* it extends V1's denormalisation instead of
  fighting it. V1 already chose `(white_player_id,
  white_display_name)` on `games` because guests have no extra
  attached data — adding a third column per side (`white_user_id`)
  is the natural next step on the same shape. The historical
  `games.{white,black}_player_id` columns stay as the audit-time
  snapshot (unconstrained UUIDs, no FK), and `*_user_id` is the
  separate "who owns this game" signal that feature 19 reads. The
  cycle-1 `players` table was solving an imaginary problem; the
  direct FK is the smallest change that delivers the actual
  product requirement.

**Decision: validator-only this feature; issuer in feature 17.**

- *What:* `JwtVerifier` exists and is exercised end-to-end via
  `/api/me`. No `JwtIssuer` ships here, no register/login
  endpoint.
- *Alternatives considered:* ship issuance + validation together
  in one feature. Rejected because the diff would be roughly
  twice the size, and validation has a clean independent test
  (the IT mints a token inline with the same library).
- *Why this one:* splitting the bundle into a "lay the
  foundation" feature 16 and a "user-visible auth flow" feature
  17 keeps each PR reviewable and lets the foundation ship with
  its own regression tests before issuance lands.

**Decision: `HttpStatusEntryPoint(401)` over a custom
`AuthenticationEntryPoint` returning `ErrorResponse`. The OpenAPI
spec for the 401 advertises an empty body to match.**

- *What:* unauthenticated requests to protected endpoints return
  `401 Unauthorized` with an empty body. The structured
  `ErrorResponse` envelope is not used at this layer.
  `MeController`'s `@ApiResponse(responseCode = "401", ...)`
  declares `content = @Content` (empty) so the OpenAPI spec
  reports the same empty body the runtime actually emits — no
  spec-vs-runtime drift.
- *Alternatives considered:* a custom entry point that writes
  the `ErrorResponse` shape (`{ error, message, timestamp }`)
  via `ObjectMapper`. Rejected because the spec doesn't enumerate
  a 401 error code in the 9-code allowlist (`ROOM_NOT_FOUND`,
  `ROOM_FULL`, ..., `MISSING_HEADER` — all 4xx, but none for
  auth failure), and adding a 10th code is out of scope for this
  feature. Feature 17 may revisit if the frontend asks for a
  parsed body on 401.
- *Cycle-2 fix:* the first pass of this feature mistakenly
  declared `@ApiResponse(... content = @Content(schema =
  @Schema(implementation = ErrorResponse.class)))` on the 401 —
  the spec advertised a payload the runtime never emits. The
  reviewer caught it. Cycle 2 corrects the annotation; the
  runtime stays unchanged. Adding an auth error code to
  `ErrorResponse` is deferred to feature 17 where issuance has
  the natural place for it.
- *Why this one:* the contract the acceptance criteria assert is
  "returns 401" — body shape is not specified. Empty body keeps
  the surface area minimal and matches the typical
  `WWW-Authenticate: Bearer` response shape, and the spec now
  matches reality.

**Decision: surefire + failsafe activate the `test` profile.**

- *What:* `pom.xml`'s surefire and failsafe plugin configs pass
  `-Dspring.profiles.active=test` so every JUnit boot of the
  Spring context picks up `application-test.yml`.
- *Alternatives considered:*
  - `@ActiveProfiles("test")` on every IT. Rejected because
    it's invasive (touch every existing IT) and easy to forget
    on new ITs.
  - Put a placeholder secret default in `application.yml`.
    Rejected because it violates the fail-fast posture the plan
    documents: a prod deploy without `AUTH_JWT_SECRET` would
    silently use the placeholder.
- *Why this one:* the test profile is activated unconditionally
  for all tests via the build system, not via per-class
  annotation. The build-system-level activation is in one place
  (pom.xml), is impossible to forget, and matches how production
  externalises configuration (env vars / system properties at
  the JVM boundary, not inside the code).

## How this compares to what I know

- **In Scala / Typelevel this would be...** an http4s app with a
  middleware layer that wraps the routes and does the JWT decode.
  The library of choice is **`tsec`** for JWT
  (`tsec-jwt-mac` for HMAC-signed tokens), which exposes
  `JWTMac[F, A]` and a typed `JWSMacCV` verifier that returns an
  `F[JWTMac[F, A]]` on success or fails the effect on bad
  signature / expiry. The middleware shape is roughly:
  ```scala
  AuthMiddleware { req =>
    OptionT.fromOption[F](req.headers.get[Authorization])
      .subflatMap(parseBearer)
      .semiflatMap(token => verifier.verifyAndParse(token))
      .semiflatMap(claims => userRepo.findById(claims.subject))
      .toOption
  }
  ```
  The Spring `JwtAuthenticationFilter` is the same idea
  imperatively: read header, parse, look up user, set context.
  The structural difference: Spring's filter sits in the servlet
  chain and runs before the controller; http4s middleware wraps
  the entire `HttpRoutes[F]` and produces a new `HttpRoutes[F]`
  with the `User` threaded into the request type. Both make the
  controller method "by-construction-authenticated" if it's
  reached.

  `AuthProperties` is the Spring equivalent of a `pureconfig`-
  derived `case class JwtConfig(secret: String, expiry: FiniteDuration)`
  loaded at the edge of the world. The compact constructor
  invariant maps cleanly to a `require(...)` inside the
  `case class`'s body or a Refined type like `String Refined
  MinSize[32]`. `@EnableConfigurationProperties` is the Spring
  equivalent of "I want this single instance available
  everywhere"; Cats Effect achieves it structurally by
  constructing the value once in `Resource[F, AppConfig]` and
  threading it through.

- **In Node this would be...** an Express / Fastify app with a
  middleware function on the routes that need auth.
  `jsonwebtoken` is the de-facto library (`jwt.verify(token,
  secret)` throws or returns the payload), parallel to jjwt.
  Typical shape:
  ```js
  function authMiddleware(req, res, next) {
    const header = req.headers.authorization;
    if (!header?.startsWith('Bearer ')) return next();   // anonymous
    try {
      const claims = jwt.verify(header.slice(7), process.env.AUTH_JWT_SECRET);
      const user = await userRepo.findById(claims.sub);
      if (user) req.user = user;
    } catch (e) { /* anonymous */ }
    next();
  }
  app.get('/api/me', requireAuth, (req, res) => res.json({...req.user}));
  ```
  The differences from Spring Security: Node middleware is
  per-route or per-app, declared in user-space without a
  framework's filter chain; Spring's filter chain is
  framework-provided and arrayed by the security configuration.
  The Node version typically reads `process.env` directly inside
  the middleware (no `@ConfigurationProperties` parallel); the
  discipline of centralising the read in one module is
  convention-only.

## Gotchas / things I learned the hard way

- **`spring-boot-starter-security` pulls `spring-security-config`
  and `spring-security-web` transitively.** You don't add
  `spring-security-config` directly. The starter is the right
  level of granularity for the standard servlet-Spring auth
  surface.
- **`HttpSecurity#csrf(csrf -> csrf.disable())` lambda form is
  the only one that doesn't trigger a deprecation warning in
  Spring Security 6.5.** The older `http.csrf().disable()`
  chained form is deprecated and will be removed in 7.x. Same
  pattern for `cors()` and friends — always use the lambda DSL.
- **Spring Security's default behaviour for unauthenticated
  requests is *not* always 401.** Depending on filter
  arrangement, it can be 403. We needed to wire an explicit
  `HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)` to lock the
  contract.
- **jjwt 0.12.x's API is `Jwts.parser().verifyWith(...).build().parseSignedClaims(token)`.**
  The older `Jwts.parserBuilder().setSigningKey(...)` shape
  works but is deprecated. Same for the builder side: `.subject(...)`
  / `.claim(...)` / `.signWith(key, Jwts.SIG.HS256)` is the new
  fluent shape.
- **`ddl-auto: validate` doesn't validate tables for which no
  JPA entity exists, or columns the entity does not declare.**
  The two new FK columns on `games` (`white_user_id`,
  `black_user_id`) are not mapped by `GameEntity` in this feature;
  Hibernate ignores them at validate time. Feature 19 will add
  the mapping when it starts populating them. The `users` table
  IS validated because `User` is a JPA entity — which is why
  `@Column(length = ...)` on every field MUST match the migration's
  `VARCHAR(N)` cap exactly. A drift on either side fails boot
  loudly with a `SchemaManagementException`.
- **The cycle-1 "intermediate `players` table" mistake — and the
  portfolio-grade lesson it carries.** The first implementation
  pass of this migration created an intermediate `players` table
  with `(id, display_name, user_id FK, kind, created_at)`,
  reading the V1 comment's foreshadowing of `V2__create_players.sql`
  as a literal instruction. The user rejected it during review:
  the V1 comment describes ONE possible auth migration path, not
  the only one, and the path it sketches contradicts V1's own
  deliberate denormalisation rationale. The corrected design adds
  the user FK columns directly to `games`, mirroring the shape V1
  established. **Lesson:** code comments that foreshadow future
  migrations are hints to think with, not blueprints to execute.
  Re-read the design rationale around them before extending — the
  rationale is the load-bearing part, the example migration name
  is decoration. This is the kind of design diff that's worth
  surfacing on a portfolio: the cycle showed the value of having
  a reviewer who pushes back on plausible-looking but
  rationale-incompatible designs before they harden into the
  codebase.
- **Activating a Spring profile via Maven surefire's
  `systemPropertyVariables` is the cleanest way to apply a
  test-only override without touching every test class.**
  `@ActiveProfiles` on a per-class basis is the alternative but
  has the easy-to-forget failure mode.

## To dig deeper

- [Spring Security reference — Authentication Architecture](https://docs.spring.io/spring-security/reference/servlet/authentication/architecture.html)
  — the canonical mental model: `SecurityFilterChain`,
  `Authentication`, `AuthenticationManager`,
  `SecurityContextHolder`.
- [Spring Security reference — Java Configuration](https://docs.spring.io/spring-security/reference/servlet/configuration/java.html)
  — the modern `SecurityFilterChain` bean idiom.
- [Spring Boot externalised configuration](https://docs.spring.io/spring-boot/reference/features/external-config.html)
  — the `@ConfigurationProperties` binding and the env-var
  override pattern that this feature uses for `auth.jwt.secret`.
- [jjwt — JJWT 0.12.x quickstart](https://github.com/jwtk/jjwt#example-jws-creation)
  — the new fluent API for building and parsing JWTs.
- [tsec — Scala JWT library](https://jmcardon.github.io/tsec/docs/jwt-mac.html)
  — the Typelevel parallel for HMAC-signed JWTs.
- [Flyway versioned migrations](https://documentation.red-gate.com/fd/migrations-184127470.html)
  — the V{n}__{name}.sql convention and the forward-only
  discipline.

## File map

**Created:**

- `src/main/java/io/github/dariogguillen/chess/domain/User.java` —
  JPA entity for the new `users` table. Mutable on purpose (JPA
  needs no-args constructor + setters), mutability contained by
  package-private setters.
- `src/main/java/io/github/dariogguillen/chess/persistence/UserRepository.java`
  — Spring Data JPA repository with `findByEmail` and
  `findByGoogleSub` derived queries.
- `src/main/java/io/github/dariogguillen/chess/config/AuthProperties.java`
  — `@ConfigurationProperties("auth.jwt")` record with
  compact-constructor invariants (secret ≥ 32 bytes, expiry > 0).
- `src/main/java/io/github/dariogguillen/chess/config/SecurityConfig.java`
  — `SecurityFilterChain` bean wiring the stateless filter chain,
  the anonymous allow-list, the JWT filter placement, the
  `HttpStatusEntryPoint(401)`, and the `BCryptPasswordEncoder`
  bean for feature 17.
- `src/main/java/io/github/dariogguillen/chess/config/security/JwtVerifier.java`
  — thin wrapper over jjwt that holds the `SecretKey` and exposes
  a single `verify(String)` throwing on failure.
- `src/main/java/io/github/dariogguillen/chess/config/security/JwtAuthenticationFilter.java`
  — `OncePerRequestFilter` that reads `Authorization: Bearer ...`,
  verifies, loads the user, and populates the `SecurityContext`.
  Tolerant of all failure modes — leaves the chain anonymous,
  lets the authorization rules decide 401.
- `src/main/java/io/github/dariogguillen/chess/web/auth/MeController.java`
  — `@RestController` exposing `GET /api/me` with `@Tag`,
  `@Operation`, and `@ApiResponse` for the 200 / 401 cases.
- `src/main/java/io/github/dariogguillen/chess/web/auth/MeResponse.java`
  — `record` DTO with `@Schema` annotations for springdoc.
- `src/main/resources/db/migration/V2__create_users_and_game_user_links.sql`
  — Flyway migration: `users` table (id, email UNIQUE,
  display_name, password_hash, google_sub, created_at) + two
  nullable FK columns on `games` (`white_user_id`,
  `black_user_id`, each `UUID NULL REFERENCES users(id)`) +
  partial unique index on `google_sub` + partial indexes on each
  new user FK column scoped to populated rows only. Replaces a
  cycle-1 design (`V2__create_users_and_player_user_link.sql`)
  that introduced an intermediate `players` table — see
  "Decisions taken" above.
- `src/test/resources/application-test.yml` — test-profile
  overrides; provides a 64-byte test secret for `auth.jwt.secret`.
- `src/test/java/io/github/dariogguillen/chess/config/security/AuthCoreIT.java`
  — five-case IT covering the validator contract (missing /
  valid / expired / malformed / wrong-signature).
- `src/test/java/io/github/dariogguillen/chess/config/BearerCorsIT.java`
  — single preflight IT pinning the Authorization header in the
  REST CORS allow-list.
- `notes/16-auth-core.md` — this note.

**Modified:**

- `pom.xml` — adds `spring-boot-starter-security` and the jjwt
  trio (api / impl / jackson), plus surefire+failsafe configs
  to activate the `test` profile by default.
- `src/main/resources/application.yml` — adds
  `auth.jwt.secret` (no default, fail-fast) and
  `auth.jwt.expiry-seconds` (default 604800).
- `src/main/java/io/github/dariogguillen/chess/config/CorsConfig.java`
  — adds `Authorization` to the REST allowed-headers list.
- `docs/architecture.md` — adds the "Authentication" section
  between the "API contract" and the "CORS" section, documenting
  the bundle scope, the `User` aggregate, the fresh-start
  identity model, the JWT model, the Spring Security wiring,
  the `/api/me` contract, and what is explicitly out of scope.

**Not modified:**

- `README.md` — public API is still anonymous-only until feature
  17 lands issuance endpoints. The plan in `progress/current.md`
  explicitly calls out the no-README change.

**Cross-repo:** none this feature. `/api/me` is unreachable
without a JWT, and JWT issuance lands in feature 17 — the
frontend's auth work begins coordinated against feature 17, not
this one.
