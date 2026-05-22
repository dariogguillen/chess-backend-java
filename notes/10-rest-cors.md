## Feature 10 — CORS configuration for REST endpoints

**Feature ID:** `rest-cors` (from `feature_list.json`)

**Status:** in progress

---

## What we built

The REST surface (`/api/**`) now emits the
`Access-Control-Allow-*` headers the browser needs to let the
deployed frontend at `https://dariogguillen.github.io` actually
talk to the backend. Until this feature shipped, every cross-origin
REST call from production failed at the preflight stage — only the
STOMP `/ws` handshake was CORS-aware, the REST side was silent. As
a structural improvement, the allowed-origin list moved out of
`WebSocketConfig` (where it lived as two hardcoded strings) into a
`@ConfigurationProperties("chess.cors")` record consumed by both
layers, so REST and STOMP cannot drift the next time a new origin
needs to be allowed.

## Java / Spring concepts that appear

- **`@ConfigurationProperties` with a record type.** Spring Boot 3
  supports records as the configuration-class shape via
  canonical-constructor binding: the framework reads the
  `chess.cors.*` namespace from the merged `Environment` and calls
  `new CorsProperties(allowedOriginPatterns)` with the bound value.
  The compact constructor enforces the invariant (non-empty list)
  and freezes the input with `List.copyOf` so callers can pass the
  list straight to consumers without defensive copies of their own.
  Compared to the `@Value("${chess.cors.allowed-origin-patterns}")`
  per-field alternative, the record is one type with one place to
  validate the whole config bundle. See
  [Spring Boot's configuration-properties reference](https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.typesafe-configuration-properties).
- **`@EnableConfigurationProperties(CorsProperties.class)`.** The
  property class is not annotated `@Component`, so it would not be
  picked up by classpath scanning on its own. Both `CorsConfig` and
  `WebSocketConfig` declare `@EnableConfigurationProperties` so that
  either configuration class on the boot path is enough to register
  the bean; Spring de-duplicates the registration, so the second
  one is a no-op. This matches the pattern `RedisConfig` /
  `RedisActiveStateProperties` already established in feature 8.
- **`WebMvcConfigurer.addCorsMappings`.** The canonical centralised
  CORS entry point in Spring MVC: one method, one `CorsRegistry`,
  one place to read and audit the whole policy. The alternative —
  `@CrossOrigin` on individual controllers — distributes the policy
  across files and makes a future tightening N edits instead of one.
  See
  [Spring MVC's CORS reference](https://docs.spring.io/spring-framework/reference/web/webmvc-cors.html).
- **`allowedOriginPatterns` vs `allowedOrigins`.** Spring 6+
  rejects `allowedOrigins("*")` combined with credentials, and
  rejects host wildcards like `http://localhost:*` from
  `allowedOrigins` entirely. `allowedOriginPatterns` is the
  pattern-aware sibling that accepts both shapes (full origin,
  wildcard host/port) and is the only valid form for our use case.
  The WebSocket side already used Patterns; the REST side matches.
- **`allowCredentials: false` posture.** When `true`, Spring writes
  `Access-Control-Allow-Credentials: true` and the browser will
  send cookies / `Authorization` headers cross-origin. We have no
  cookies and no auth header, so `false` is the honest default and
  removes a foot-gun: a future change cannot accidentally start
  sharing credentials by simply listing a new origin.
- **CORS as a `HandlerInterceptor`-style filter.** Spring's CORS
  handling sits in the dispatcher chain, in front of every
  controller, intercepting OPTIONS preflights and post-processing
  matching responses to inject `Access-Control-Allow-*`. The
  policy is checked against the registered mapping (`/api/**` in
  our case) before the controller is even consulted — this is what
  makes the drift-canary IT meaningful: the framework, not the
  application, returns 403 on a disallowed preflight.

## Decisions taken

**Single source of truth via `@ConfigurationProperties`, not two hardcoded lists.**

- Decision: extract the allowed-origin patterns to
  `CorsProperties` and have both `CorsConfig` (REST) and
  `WebSocketConfig` (STOMP) read from it.
- Alternatives: keep the WS list hardcoded and add a second
  hardcoded list to the REST config "for now".
- Why: two hardcoded copies of the same policy will drift the
  moment a new origin lands (a Vercel preview, a staging domain).
  We have already lived through that pattern at the team-level —
  it always ends with a "wait, which one is canonical?" debug
  session. The property class costs one extra file now and pays
  back on every future origin edit.

**`WebMvcConfigurer.addCorsMappings`, not `@CrossOrigin` per controller.**

- Decision: register the policy once in a `WebMvcConfigurer`.
- Alternatives: `@CrossOrigin(origins = {...})` on each
  `@RestController` (or even individual methods).
- Why: the policy applies uniformly to `/api/**`; there is no
  controller that needs a different origin set. Distributing the
  same annotation across N controllers makes the policy harder to
  audit and easy to forget on a new controller. The configurer is
  one place, the surface is one prefix, the rule is one method.

**CORS at the application layer, not at Caddy.**

- Decision: Spring emits the headers; Caddy passes through.
- Alternatives: configure `header_up` / `header_down` directives
  in the Caddyfile to inject CORS at the proxy.
- Why: local dev would still need its own CORS layer (no Caddy in
  front), so the proxy approach forces two implementations.
  Keeping CORS in the app means the same artifact behaves the same
  way under `./mvnw spring-boot:test-run`, `docker compose up`,
  and EC2-behind-Caddy. Caddy stays purely a TLS terminator and
  forwarder.

**`allowedOriginPatterns`, not `allowedOrigins`.**

- Decision: use the pattern-aware variant.
- Alternatives: `allowedOrigins(...)` with the same string list.
- Why: `http://localhost:*` is a wildcard host pattern; Spring
  rejects it from `allowedOrigins` outright. We could enumerate
  the dev ports explicitly (`http://localhost:5173`,
  `http://localhost:3000`, …) but that brittles the dev workflow.
  Patterns are the canonical form for this case, the WS side
  already uses them, and the alphabet is the same on both layers.

**`allowCredentials: false`.**

- Decision: cookies / `Authorization` are NOT shared cross-origin.
- Alternatives: `true` "in case we add auth later".
- Why: there are no cookies and no auth header in the codebase
  today. Setting `true` preemptively would advertise a capability
  that does not exist and would silently start sharing credentials
  the day a future feature adds them. The flip is a deliberate
  decision tied to the auth feature, not a side effect of CORS
  scaffolding.

**Allowed headers: `Content-Type, Accept` only.**

- Decision: keep the list minimal.
- Alternatives: include `Authorization` "for forward compatibility".
- Why: the codebase has no auth header anywhere. Allow-listing it
  here would imply functionality we have not built and is dead
  config. When auth lands, the list grows as part of that feature.

**`maxAge: 3600` (1 hour).**

- Decision: cache the preflight for one hour on the browser side.
- Alternatives: lower (10 minutes), higher (24h), or unset.
- Why: 1 hour is the standard conservative value across Spring's
  own examples and the most common third-party guides. Long
  enough to amortise the preflight cost during an active session,
  short enough that a policy change propagates within an hour
  without operator action.

**`http://localhost:*` wildcard for dev.**

- Decision: allow any localhost port in dev by default.
- Alternatives: enumerate the dev ports explicitly (`5173`,
  `3000`, `8080`, …).
- Why: local dev is not an attack surface; the developer running
  the frontend already trusts every process on their machine.
  Wildcarding the dev origin removes a class of "oh my Vite picked
  a different port today" friction without widening the
  production surface (the wildcard does not affect the production
  GitHub Pages entry).

## How this compares to what I know

- **`@ConfigurationProperties` ≈ pureconfig's
  `ConfigSource.default.load[CorsConfig]`.** Same idea: parse a
  config namespace into a typed case-class shape, with derivation
  on the constructor signature. Spring's binding is reflective
  rather than typeclass-derived, and the failure mode lives in the
  compact constructor instead of a `ConfigReader[F]` instance, but
  the developer experience is the same — declare the shape, let
  the framework do the I/O.
- **`WebMvcConfigurer.addCorsMappings` ≈ http4s's
  `CORS.policy[F](...)` middleware applied at the routes level.**
  Both are centralised filters that wrap the routing table, both
  inject the `Access-Control-Allow-*` headers on the way out and
  short-circuit OPTIONS preflights on the way in. The difference is
  declarative-by-annotation (Spring) vs `httpApp =>
  CORS.policy.apply(httpApp)` (http4s), but the architectural
  position is identical.
- **`allowedOriginPatterns` ≈ the regex/glob mode of http4s's
  `CORSPolicy.withAllowOriginHostCi`.** Pattern matching on origins
  rather than literal equality. Same trade-off both sides: the
  pattern form is required for any wildcard, and the policy author
  pays a small cognitive cost (what does the pattern actually
  match?) in exchange for not enumerating every dev port.
- **`allowCredentials: false` ≈ http4s's default
  `CORSPolicy.withAllowCredentialsDisable`.** Same security
  posture, expressed as a builder method vs an annotation parameter.
- **vs Node + Express's `cors` middleware.** `app.use(cors({
  origin: [...], credentials: false }))` is the same shape: a
  per-app filter that handles preflight and post-processes
  responses. The drift problem ("the WS handshake had a different
  origin list than the REST surface") is also identical in Node —
  except Node typically does not have a strong typed-config story,
  so the property-class fix here is one of the places where Java +
  Spring's verbosity actually buys something.

## Gotchas / things I learned the hard way

- **`@EnableConfigurationProperties` needs to land somewhere on
  the boot path even though the property class is annotated.**
  `@ConfigurationProperties` alone does not register the bean —
  Spring Boot's auto-config picks up the annotation through the
  scanning step, but the recipe both feature 8 (`RedisConfig`) and
  this feature use is to put `@EnableConfigurationProperties(...)`
  on the `@Configuration` class that consumes it. Belt and braces.
- **The drift-canary test must assert the absence of the allow
  header, not just a 403.** Spring returns 403 on a disallowed
  preflight (`CorsConfiguration.checkOrigin` returns null →
  `DefaultCorsProcessor` rejects), but a misconfigured CORS
  registry could also produce 403 with the header set. Asserting
  `header().doesNotExist(...)` is the strictly stronger check.
- **`setAllowedOriginPatterns` and `addCorsMappings` use slightly
  different array shapes.** The STOMP side takes a `String...`
  varargs; the MVC side takes a `String...` too via the
  `CorsRegistry` builder. Both end up calling
  `props.allowedOriginPatterns().toArray(String[]::new)` — the
  varargs hides the array conversion, but the underlying call is
  the same on both sides.
- **`http://localhost:*` does NOT match `http://localhost`
  (without a port).** The pattern asserts a port wildcard, not "port
  optional". This is fine in practice — dev frontends always run
  on a specific port — but worth knowing if a curl-from-the-host
  test ever uses the bare hostname.

## To dig deeper

- [Spring MVC's CORS reference](https://docs.spring.io/spring-framework/reference/web/webmvc-cors.html)
  — the canonical doc, covers `WebMvcConfigurer`,
  `addCorsMappings`, and the difference between
  `allowedOriginPatterns` and `allowedOrigins`.
- [Spring Boot externalised configuration](https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.typesafe-configuration-properties)
  — how `@ConfigurationProperties` binds, including record
  support and the rules for `@EnableConfigurationProperties`.
- [MDN — CORS](https://developer.mozilla.org/en-US/docs/Web/HTTP/CORS)
  — the spec-level mental model: preflight, simple requests,
  credentialed requests, the role of `Access-Control-Max-Age`.
- [pureconfig](https://pureconfig.github.io/) — the Scala
  parallel for typed configuration loading.

## File map

**Created:**

- `src/main/java/io/github/dariogguillen/chess/config/CorsProperties.java`
  — `@ConfigurationProperties("chess.cors")` record holding the
  allowed-origin-patterns list, with non-empty invariant.
- `src/main/java/io/github/dariogguillen/chess/config/CorsConfig.java`
  — `@Configuration` implementing `WebMvcConfigurer`, registers
  the `/api/**` CORS mapping.
- `src/test/java/io/github/dariogguillen/chess/config/CorsConfigIT.java`
  — 4 ITs: preflight allowed (GitHub Pages + localhost), drift
  canary (disallowed origin omits the header), real cross-origin
  POST.
- `notes/10-rest-cors.md` — this note.

**Modified:**

- `src/main/java/io/github/dariogguillen/chess/config/WebSocketConfig.java`
  — constructor-injects `CorsProperties`, replaces the hardcoded
  origin list with `cors.allowedOriginPatterns().toArray(...)`.
  JavaDoc now points at `CorsProperties` as the source of truth.
- `src/main/resources/application.yml` — adds `chess.cors.allowed-origin-patterns`
  with the `CHESS_CORS_ALLOWED_ORIGIN_PATTERNS` env-var default
  matching the previous hardcoded WS list.
- `docs/architecture.md` — new top-level `CORS` section covering
  the shared property, the REST and STOMP policies, and the Caddy
  pass-through; the old STOMP-side "Allowed origins" subsection
  becomes a pointer to the new section.

**Cross-repo:** closes `chess-frontend`'s cross-repo item #1
(production E2E blocked by missing REST CORS). The frontend does
not change — the browser simply stops blocking the preflight.
