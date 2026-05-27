# Feature 17 — Email/password registration + login → JWT issuance (`auth-jwt`)

**Feature ID:** `auth-jwt` (from `feature_list.json`)

**Status:** in progress

---

## What we built

The user-visible half of the auth bundle. Two new POST endpoints —
`/api/auth/register` and `/api/auth/login` — mint HS256 JWTs that the
feature-16 `JwtAuthenticationFilter` validates without coordination
beyond the shared `AuthProperties` bean. Three new error codes
(`AUTHENTICATION_REQUIRED`, `EMAIL_ALREADY_TAKEN`,
`INVALID_CREDENTIALS`) bring the 4xx vocabulary to 12. The 401 entry
point that feature 16 left as a placeholder is now a custom
`AuthEntryPoint` that emits the same structured `ErrorResponse`
envelope the rest of the API uses.

After this feature ships, the auth surface is "registered users can
sign in and receive a JWT that the rest of the backend recognises".
Per-user data (game history, identity-bound game creation, STOMP
trust) is still ahead in features 19 and 20.

## Java / Spring concepts that appear

- **`BCryptPasswordEncoder` cost factor and 72-byte input cap.**
  BCrypt's algorithm is structurally a 72-byte block cipher; any
  input beyond 72 bytes is silently truncated. The Spring default
  cost factor is 10 (about 100ms per hash on a modern x86). Cost is
  the trade-off between login latency and offline-cracking resistance
  — pushing to 12+ multiplies the per-login CPU cost by 4 and is the
  right move when the user count justifies it. We capped
  `RegisterRequest.password` at `@Size(max = 72)` so a user is told
  the password is too long instead of being given a silently weakened
  hash. See [Spring Security reference — Password
  Storage](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html).
- **jjwt 0.12.x's `Jwts.builder()` fluent API.** Symmetric to feature
  16's `Jwts.parser()` shape. The new (post-0.11) idiom is
  `Jwts.builder().subject(...).claim("k", v).issuedAt(date).expiration(date).signWith(key, Jwts.SIG.HS256).compact()`.
  The older `setSubject` / `setClaims` / `setSigningKey` chain still
  works but is on its way out. The `Jwts.SIG.HS256` constant replaces
  the older `SignatureAlgorithm.HS256` enum. See
  [jjwt docs](https://github.com/jwtk/jjwt#jws-create).
- **`@Transactional` and Hibernate's race-window safety net.** The
  annotation declares an AOP-proxied transactional boundary around
  `AuthService.register` — Spring wraps the bean in a CGLIB proxy
  that opens a transaction before the method and commits / rolls
  back after it. The `findByEmail`+`save` pair is one logical write,
  so the boundary is correct. The race window between two concurrent
  registrations of the same email is still open (both could pass the
  `findByEmail` check before either inserts) — the safety net is the
  database-level `UNIQUE` constraint on `users.email`, which makes
  the second insert throw `DataIntegrityViolationException`. We
  catch it and rethrow `EmailAlreadyTakenException` so the client
  sees the same structured 409 regardless of which branch triggered.
- **Custom `AuthenticationEntryPoint` and where it sits.** The entry
  point is the bean Spring Security calls when an `AccessDeniedException`
  (or its `AuthenticationException` sibling) bubbles up from a
  filter and the chain decides the request must surface as 401. The
  default `HttpStatusEntryPoint(401)` just writes the status code;
  our `AuthEntryPoint` writes a structured `ErrorResponse` body via
  the same `ObjectMapper` and `Clock` beans `GlobalExceptionHandler`
  uses, so the wire format is identical across the 4xx surface. See
  [Spring Security reference —
  AuthenticationEntryPoint](https://docs.spring.io/spring-security/reference/servlet/authentication/architecture.html#servlet-authentication-authenticationentrypoint).
- **Jakarta Validation flow.** `@Valid @RequestBody RegisterRequest`
  triggers Hibernate Validator's bean validation. A constraint
  failure throws `MethodArgumentNotValidException` which
  `GlobalExceptionHandler.handleValidation` catches and maps to 400
  with the message `<field>: <constraint message>`. The annotations
  on the record components (`@Email`, `@NotBlank`, `@Size`) are
  reflected in the OpenAPI spec automatically by springdoc, so the
  Swagger UI shows the constraints without manual `@Schema`
  description duplication. See
  [Jakarta Bean Validation 3.0
  spec](https://jakarta.ee/specifications/bean-validation/3.0/).
- **Mechanical error-code derivation.**
  `GlobalExceptionHandler.codeOf(ChessException)` strips the trailing
  `"Exception"` and converts camelCase to UPPER_SNAKE_CASE. So
  `EmailAlreadyTakenException` → `EMAIL_ALREADY_TAKEN`,
  `InvalidCredentialsException` → `INVALID_CREDENTIALS`. New
  exception classes that extend `ChessException` get the right code
  for free — no per-class branching needed. The drift canary in
  `OpenApiIT.errorResponseSchema_*` keeps the
  `@Schema(allowableValues = {...})` array in lock-step with the
  emitted codes.

## Decisions taken

**Decision: `JwtIssuer` derives the `SecretKey` independently from
the same `AuthProperties.secret`, instead of depending on the
existing `JwtVerifier` bean.**

- *What:* both classes call
  `Keys.hmacShaKeyFor(props.secret().getBytes(UTF_8))`. The key bytes
  are byte-identical because the function is pure and both classes
  consume the same `AuthProperties` bean. No third "shared key" bean.
- *Alternatives considered:*
  - **`JwtIssuer` injects `JwtVerifier` and reads a `SecretKey`
    accessor from it.** Rejected because it would require widening
    `JwtVerifier`'s API to expose the key (or refactoring its
    private field), which is gratuitous coupling.
  - **A `SecretKey` `@Bean` in a new config class that both consume.**
    Rejected because the derivation is one line; introducing a bean
    just to share that line obscures the relationship rather than
    clarifying it. Two classes that derive the same value from the
    same input are easier to read than two classes that depend on a
    third bean.
- *Why this one:* correctness is obvious. The two classes read the
  same property from the same bean and run the same library function
  on it; there is no integration test required to prove they agree.
  The round-trip IT (`roundTrip_registerThenLogin_thenMeReturnsSameUser`)
  pins the user-visible contract: a token from `/api/auth/login` is
  accepted by `/api/me`. If the key derivation ever diverged, that
  test would fail loudly.

**Decision: uniform 401 response on login failure, with constant-time
timing.**

- *What:* both `login_wrongPassword` and `login_unknownEmail` throw
  `InvalidCredentialsException` with the message
  `"Invalid email or password"`. The IT
  `login_unknownEmail_returnsSameBodyAsWrongPassword` asserts the
  two responses match field-by-field (excluding the legitimately
  differing `timestamp`). `AuthService.authenticate` also runs a
  BCrypt comparison against a pre-computed dummy hash on the
  unknown-email branch so the response time does not leak whether
  the email exists.
- *Alternatives considered:*
  - **Distinct codes / messages for "unknown email" vs "wrong
    password".** Rejected because it lets an attacker enumerate
    registered email addresses by attempting logins with throwaway
    passwords. This is a well-known anti-pattern in the auth
    literature (OWASP authentication cheat sheet, the "user
    enumeration" entry).
  - **Skip the dummy-hash comparison on the unknown-email branch.**
    Rejected because BCrypt is intentionally slow (~100ms at cost
    factor 10), and short-circuiting on "no such user" would create
    a detectable timing channel (the request returns in ~1ms instead
    of ~100ms).
- *Why this one:* defence in depth. The codes and messages match by
  design at the application layer, and the timing matches by design
  at the BCrypt layer. An attacker observing either signal sees the
  same thing for any failed login attempt.

**Decision: `LoginRequest.password` has no `@Size` constraint, even
though `RegisterRequest.password` does.**

- *What:* the registration form enforces 8-72 chars; login does not.
  A wrong-length password on login surfaces as 401
  `INVALID_CREDENTIALS`, not 400 `VALIDATION_FAILED`.
- *Alternatives considered:* mirror `@Size(min = 8, max = 72)` on
  login for symmetry. Rejected because the 400 leaks the
  password-length policy: an attacker who tries to log in with a
  4-character password and gets "password too short" knows the
  minimum is at least 5.
- *Why this one:* the login surface deliberately does not tell the
  caller anything about the password they tried — not whether it was
  the right length, not whether the email exists, not which BCrypt
  cost factor we use. Only "invalid credentials". The registration
  surface is fine to enforce constraints because by definition the
  caller is not authenticating, they are stating their intent.

**Decision: `EmailAlreadyTakenException` extends the existing
`ConflictException`; no new umbrella for `InvalidCredentialsException`.**

- *What:* `EmailAlreadyTakenException` extends `ConflictException`,
  inheriting the 409 mapping and the mechanical code derivation in
  `GlobalExceptionHandler.handleConflict`. `InvalidCredentialsException`
  extends `ChessException` directly with a narrow
  `@ExceptionHandler(InvalidCredentialsException.class)` method that
  maps to 401.
- *Alternatives considered:* introduce an `UnauthorizedException`
  umbrella so the handler can target the family. Rejected because
  the codebase has exactly one 401 from a domain exception today; an
  umbrella for a single member is premature abstraction. If a second
  401 case shows up, the umbrella can be promoted at that point
  without churning this class.
- *Why this one:* the existing `handleConflict` already does the
  right thing for `EmailAlreadyTakenException` (codeOf gives
  `EMAIL_ALREADY_TAKEN`, status is 409). The narrow handler for the
  401 case keeps the surface area minimal until evidence justifies
  more.

**Decision: pass the validated `RegisterRequest` / `LoginRequest` web
DTOs directly into `AuthService`, no separate `Command` records.**

- *What:* `AuthService.register(RegisterRequest req)` and
  `AuthService.authenticate(LoginRequest req)`. The records are
  immutable, validated at the controller boundary, and carry only
  the fields the service needs.
- *Alternatives considered:* dedicated `RegisterCommand` /
  `LoginCommand` records in the service package so the web layer
  does not leak through. Rejected because (a) the project's
  existing services (`RoomService.joinRoom`, etc.) accept primitive
  parameters without a Command record, and (b) the request DTOs
  carry no web-specific machinery — they are plain records with
  Jakarta Validation annotations the service does not even see at
  runtime.
- *Why this one:* simplicity. Introducing a parallel set of records
  would double the type count without adding any guarantee the
  current shape lacks.

## How this compares to what I know

- **In Scala / Typelevel this would be...** an http4s app whose
  routes use a JWT-issuance / verification pair from `tsec` (the
  HMAC-signed equivalent is `tsec-jwt-mac`). The issuer side maps
  cleanly to a `JWTMacImpure` build:
  ```scala
  for {
    claims <- JWTClaims.withDuration[F](
      issuer = "chess-backend".some,
      subject = user.id.toString.some,
      customFields = Seq("email" -> Json.fromString(user.email)),
      expiration = 7.days.some
    )
    token  <- JWTMac.build[F, HMACSHA256](claims, key)
  } yield token.toEncodedString
  ```
  The construction parallel is exact: same claim set (`sub`, custom
  `email`, `iat`, `exp`), same key derivation
  (`HMACSHA256.buildKey(secretBytes)`), same algorithm
  identifier. The structural difference: tsec returns the typed
  `F[JWTMac[F, HMACSHA256]]`, jjwt returns a `String`. Both libraries
  treat the key as the load-bearing part — once you have a key and
  an algorithm, both `JWTMac.build` and `Jwts.builder().signWith` are
  the obvious dual of their verifier.

  The transactional semantics map to **doobie**'s `ConnectionIO[A]`:
  effectful operations against the same connection compose with
  `flatMap`, and `.transact(xa)` is the boundary at which the
  effects either all commit or all roll back. Spring's
  `@Transactional` proxy is doing the same thing imperatively — the
  proxy opens a JDBC `Connection`, sets autocommit off, runs the
  method body, commits on normal return or rolls back on a thrown
  `RuntimeException`. The `DataIntegrityViolationException` catch in
  `AuthService.register` is the Java equivalent of doobie's
  `Either[SQLException, A]` recovery — `recoverWith` would catch the
  constraint violation and lift it into a domain error in the same
  shape.

  Argon2 vs BCrypt is the same debate in both ecosystems. tsec
  bundles both (`tsec-password` exposes `argon2` and `bcrypt`).
  Argon2id is the modern winner of the Password Hashing Competition
  (2015) and is memory-hard, which makes GPU-cracking expensive in a
  way BCrypt isn't. BCrypt is still the default Spring Security
  exposes and the de-facto industry baseline because it's been
  battle-tested for two decades. The portfolio trade-off: argon2
  would be the more defensible choice on a green-field auth surface
  today, but BCrypt is what Spring Security ships as a `@Bean`, and
  switching is a future-feature concern. Both libraries have the
  same input cap to be careful of — argon2 doesn't have BCrypt's
  72-byte block-size limit, but it has its own memory/time/parallelism
  parameters to tune.

- **In Node this would be...** an Express or Fastify route handler
  pair using `bcrypt` / `bcryptjs` for hashing and `jsonwebtoken` for
  the JWT.
  ```js
  app.post('/api/auth/register', async (req, res) => {
    const { email, password, displayName } = registerSchema.parse(req.body);
    const hash = await bcrypt.hash(password, 10);
    try {
      const user = await prisma.user.create({
        data: { id: randomUUID(), email: email.toLowerCase(),
                passwordHash: hash, displayName, createdAt: new Date() }
      });
      const token = jwt.sign(
        { sub: user.id, email: user.email },
        process.env.AUTH_JWT_SECRET,
        { expiresIn: '7d' }
      );
      res.status(201).json({ token, user: toMeResponse(user) });
    } catch (e) {
      if (e.code === 'P2002') throw new ConflictError('EMAIL_ALREADY_TAKEN', ...);
      throw e;
    }
  });
  ```
  The validator (`zod`, `class-validator`, etc.) is the Jakarta
  Validation parallel, sitting at the route boundary. Prisma's
  `P2002` is the Postgres-side `UNIQUE` constraint exception the
  ORM lifts into a typed error — same role as Spring's
  `DataIntegrityViolationException`. Node's transactional story is
  weaker than Spring's: `prisma.$transaction([...])` accepts an
  array of operations and runs them in a single DB transaction, but
  there is no `@Transactional` annotation at the function level —
  the developer has to remember to wrap each call site. Spring's
  declarative proxy is one of the few places the Java framework
  genuinely beats the Node ecosystem on ergonomics.

## Gotchas / things I learned the hard way

- **`OpenApiIT.errorResponseSchema_*` is a drift canary, not a
  side effect.** Adding three codes meant updating both the
  `@Schema(allowableValues = ...)` on `ErrorResponse` AND the
  expected-list assertion in the IT. Forget one and the build fails;
  this is exactly the point. The test was deliberately tightened in
  feature 6.6 so that changes to the error vocabulary are visible
  in one diff line each.
- **`MeController`'s 401 `@ApiResponse` was set to empty content
  in feature 16 cycle 2 deliberately — it had to be re-flipped back
  to `ErrorResponse` here.** The spec/runtime alignment matters: an
  empty-body claim with a structured-body emit (or vice versa) would
  drift the OpenAPI consumers (the frontend's `openapi-typescript`
  codegen) off the actual contract. Worth re-reading feature 16's
  decision note before changing the entry point — it documents the
  reasoning that this feature inverts.
- **`@PostMapping(consumes = APPLICATION_JSON_VALUE)` matters for
  the spec.** Without it, springdoc reports the operation accepts
  `*/*`, which is technically true (Spring's default is permissive)
  but is wrong for the spec consumer. Pinning the consumed media
  type makes the OpenAPI `requestBody.content` accurate.
- **The dummy-hash trick relies on `BCryptPasswordEncoder.matches`
  not throwing on a malformed hash.** The pre-computed
  `$2a$10$N9qo8uLOick...` string is a real BCrypt-formatted output,
  so the encoder accepts it and runs the comparison. A made-up
  string like `"NOT_A_HASH"` would log a warning and return false
  immediately — the timing channel would be wide open. Use a real
  BCrypt-shaped string.
- **Spring's `@AuthenticationPrincipal` resolves `null` if the
  context is anonymous.** That is a non-issue here because the entry
  point intercepts before the controller runs, but it is a footgun
  on endpoints that are sometimes-authenticated. The defensive
  pattern is to either set up an `AnonymousAuthenticationToken` in
  the filter or check for null in the controller — the latter is
  the simpler choice for the few endpoints that will need it
  (feature 19's `POST /api/games` is the next candidate).

## To dig deeper

- [Spring Security reference — Password
  Storage](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html)
  — the canonical mental model for how `PasswordEncoder` fits into
  the auth flow, plus the cost-factor guidance for BCrypt.
- [Spring Security reference —
  AuthenticationEntryPoint](https://docs.spring.io/spring-security/reference/servlet/authentication/architecture.html#servlet-authentication-authenticationentrypoint)
  — what the entry point is for, how it differs from
  `AccessDeniedHandler`.
- [jjwt — JWS creation](https://github.com/jwtk/jjwt#jws-create)
  — the fluent `Jwts.builder()` API used by `JwtIssuer`.
- [Jakarta Bean Validation 3.0
  spec](https://jakarta.ee/specifications/bean-validation/3.0/) —
  the constraint annotations and the
  `MethodArgumentNotValidException` flow Spring MVC threads through.
- [OWASP Authentication Cheat
  Sheet — Authentication and Error
  Messages](https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html#authentication-and-error-messages)
  — the user-enumeration rationale for the uniform 401 message.
- [Spring Data JPA —
  Transactions](https://docs.spring.io/spring-data/jpa/reference/repositories/transactions.html)
  — `@Transactional` propagation, isolation, and rollback rules.

## File map

**Created:**

- `src/main/java/io/github/dariogguillen/chess/config/security/JwtIssuer.java`
  — issuance counterpart to `JwtVerifier`; same `SecretKey`
  derivation, injects `Clock` for testable timestamps.
- `src/main/java/io/github/dariogguillen/chess/config/security/AuthEntryPoint.java`
  — custom `AuthenticationEntryPoint` that writes `ErrorResponse`
  with `AUTHENTICATION_REQUIRED`. Reuses `ObjectMapper` + `Clock`.
- `src/main/java/io/github/dariogguillen/chess/service/auth/AuthService.java`
  — new `service/auth/` package. `@Transactional` `register`; uniform
  failure `authenticate` with constant-time dummy-hash guard.
- `src/main/java/io/github/dariogguillen/chess/web/auth/AuthController.java`
  — REST endpoints for `/api/auth/register` (201) and
  `/api/auth/login` (200). Full springdoc annotations.
- `src/main/java/io/github/dariogguillen/chess/web/auth/RegisterRequest.java`
  — Jakarta-validated record (`@Email`, `@NotBlank`, `@Size(8..72)`
  on password, `@Size(max=100)` on display name).
- `src/main/java/io/github/dariogguillen/chess/web/auth/LoginRequest.java`
  — Jakarta-validated record; deliberately no `@Size` on password.
- `src/main/java/io/github/dariogguillen/chess/web/auth/AuthResponse.java`
  — record `(token, MeResponse user)` reused for both register and
  login responses.
- `src/main/java/io/github/dariogguillen/chess/exception/EmailAlreadyTakenException.java`
  — extends `ConflictException`; 409 / `EMAIL_ALREADY_TAKEN`.
- `src/main/java/io/github/dariogguillen/chess/exception/InvalidCredentialsException.java`
  — extends `ChessException`; no-args constructor; uniform message.
- `src/test/java/io/github/dariogguillen/chess/web/auth/AuthEndpointsIT.java`
  — 9 IT cases covering happy path, validation, conflict, uniform
  401, register→login→/api/me round-trip.
- `notes/17-auth-jwt.md` — this note.

**Modified:**

- `src/main/java/io/github/dariogguillen/chess/exception/ErrorResponse.java`
  — `@Schema(allowableValues = {...})` grows from 9 codes to 12;
  adds `AUTHENTICATION_REQUIRED`, `EMAIL_ALREADY_TAKEN`,
  `INVALID_CREDENTIALS`.
- `src/main/java/io/github/dariogguillen/chess/exception/GlobalExceptionHandler.java`
  — new `@ExceptionHandler(InvalidCredentialsException.class)`
  mapping to 401 / `INVALID_CREDENTIALS`. The `ConflictException`
  branch already handles `EmailAlreadyTakenException` correctly via
  `codeOf` (no code change needed there).
- `src/main/java/io/github/dariogguillen/chess/config/SecurityConfig.java`
  — adds `/api/auth/register` and `/api/auth/login` to the anonymous
  allow-list; swaps `HttpStatusEntryPoint(401)` for the injected
  `AuthEntryPoint` bean.
- `src/main/java/io/github/dariogguillen/chess/web/auth/MeController.java`
  — restores the 401 `@ApiResponse` to reference `ErrorResponse`
  (reverts feature 16 cycle 2's empty-content placeholder).
- `src/test/java/io/github/dariogguillen/chess/config/security/AuthCoreIT.java`
  — `me_withoutAuthHeader_returns401` renamed and updated to assert
  the new structured body. Other 4 cases unchanged.
- `src/test/java/io/github/dariogguillen/chess/config/OpenApiIT.java`
  — `errorResponseSchema_listsExactlyThe*KnownErrorCodes` renamed +
  expected list grown to 12 codes.
- `docs/architecture.md` — "Authentication" section gains the
  issuance flow + the 401 entry point subsection; "API contract"
  error-code table grows by 3 rows.
- `README.md` — adds an "Authentication (optional)" subsection to
  the API section; replaces the "181 tests" claim with the new
  count.

**Not modified:**

- `pom.xml` — feature 16 added all required dependencies
  (spring-boot-starter-security, jjwt trio, validation).
- `src/main/resources/application.yml` — `auth.jwt.*` already wired
  by feature 16; `JwtIssuer` reads from the same bean.
- `src/test/resources/application-test.yml` — same secret used by
  both verifier and issuer; no test-only config needed for issuance.

**Cross-repo:** the new endpoints are additive — no existing
endpoint changes shape — so the backend ships independently. The
frontend's auth UI feature will coordinate against `/api/auth/register`
and `/api/auth/login` as documented in the Swagger UI.
