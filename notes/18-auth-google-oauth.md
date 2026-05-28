# Feature 18 — Google OAuth 2.0 sign-in (`auth-google-oauth`)

**Feature ID:** `auth-google-oauth` (from `feature_list.json`)

**Status:** in progress

---

## What we built

The third feature of the auth bundle (16-20). Adds Google as a
second sign-in path on top of feature 17's email/password issuance.
Spring Security's `oauth2Login` DSL handles the authorization
redirect to Google, the code-for-token exchange, and the userinfo
fetch; a new custom `OAuth2SuccessHandler` closes the loop by
find-or-creating our domain `User` keyed by Google's `sub` claim,
minting a JWT via the existing `JwtIssuer` from feature 17, and
redirecting the browser to the configured frontend with the token
in the URL fragment (`…/auth/callback#token=<jwt>`). The two
sign-in paths (email/password and Google OAuth) converge on the
same `User` aggregate, the same JWT shape, and the same
`JwtAuthenticationFilter`.

## Java / Spring concepts that appear

- **`oauth2Login` DSL on `HttpSecurity`.** Spring Security 6's
  declarative way to wire an OAuth 2.0 client. `http.oauth2Login(oauth
  -> oauth.successHandler(handler))` is enough — the framework
  registers `/oauth2/authorization/{provider}` (the entry point that
  redirects to the IdP's authorization endpoint) and
  `/login/oauth2/code/{provider}` (the callback that receives the
  authorization code) automatically. The provider list is read from
  `spring.security.oauth2.client.registration.*` in `application.yml`;
  Google is built-in via `CommonOAuth2Provider.GOOGLE` so no
  `provider.google.*` block is needed — the standard endpoints
  (`accounts.google.com`, etc.) are hard-coded in the constant. See
  [Spring Security reference — OAuth 2.0 Client](https://docs.spring.io/spring-security/reference/servlet/oauth2/client/index.html).
- **`CommonOAuth2Provider` constants.** `GOOGLE`, `GITHUB`, `FACEBOOK`,
  `OKTA` are the providers Spring Security ships with pre-baked
  configuration (authorization endpoint, token endpoint, userinfo
  endpoint, JWK Set URI, user-name attribute). For Google we get the
  full set for free; adding GitHub later would be a
  `spring.security.oauth2.client.registration.github` block referencing
  the same constant. See
  [`CommonOAuth2Provider` source](https://github.com/spring-projects/spring-security/blob/main/config/src/main/java/org/springframework/security/config/oauth2/client/CommonOAuth2Provider.java).
- **`AuthenticationSuccessHandler` vs the default.** Spring's default
  success behaviour after OAuth login is to redirect to the saved
  request URI (if any) or to `/`. That's wrong for our SPA — we want
  to hand the JWT to the frontend running on a different origin. The
  custom handler implements
  `AuthenticationSuccessHandler.onAuthenticationSuccess` and writes a
  302 with a fragment-token URL. The framework calls it on every
  successful OAuth completion; the SPA never sees the OAuth flow
  directly, just the final redirect.
- **`OAuth2AuthenticationToken` and `OAuth2User`.** After a successful
  OAuth callback, Spring Security passes the configured success
  handler an `Authentication` whose runtime type is
  `OAuth2AuthenticationToken`. Its principal is an `OAuth2User` (more
  specifically `DefaultOAuth2User`) carrying the userinfo attributes
  as a `Map<String, Object>`. Google sends at least `sub`, `email`,
  and `name`; we read all three via
  `principal.getAttribute("sub")`. See
  [Spring Security reference — OAuth2User](https://docs.spring.io/spring-security/reference/servlet/oauth2/client/authentication/oauth2-user.html).
- **`DefaultRedirectStrategy` for the response redirect.** A thin
  wrapper around `response.sendRedirect(url)` that runs the URL
  through `response.encodeRedirectURL` first (a JSESSIONID-rewriting
  hook from the old session-cookie days; harmless for us). Using
  `RedirectStrategy` is the Spring Security idiom for filter / handler
  code; controllers use `ResponseEntity.status(302).header(...)` or a
  `RedirectView` instead.
- **`{baseUrl}` template in `redirect-uri`.** Spring Security expands
  `{baseUrl}` at request time to the actual scheme + host + port of
  the current request. So the literal config `{baseUrl}/login/oauth2/code/google`
  resolves to `https://chess-backend.duckdns.org/login/oauth2/code/google`
  in production and `http://localhost:8080/login/oauth2/code/google`
  locally — the same image runs in both contexts without rebuilding.
  The Google Cloud Console must list both URIs as authorised redirect
  targets for this to work.
- **Nested `@ConfigurationProperties` records.** `AuthProperties` now
  binds the entire `auth.*` namespace and exposes two nested records:
  `Jwt(secret, expirySeconds)` and `OAuthProps(frontendRedirectBase)`.
  Each compact constructor validates its own subtree, so a blank
  secret OR a blank OAuth base URL fails at bean-binding time. The
  feature-16/17 API (`authProperties.secret()`,
  `authProperties.expirySeconds()`) is preserved via convenience
  accessors that delegate to the nested `Jwt` — no consumer change.

## Decisions taken

**Decision: identity-collision policy is "Option B" — soft fragment
error, not silent merge.**

- *What:* when the Google flow brings a fresh `sub` but the email is
  already taken by an existing `User` (email/password account or a
  different Google account that somehow shares the same email), the
  handler redirects to `${frontend-redirect-base}/auth/callback#error=email_taken`.
  No new `User` row is created; the existing row is not mutated.
- *Alternatives considered:*
  - **Option A: silently merge** — find the existing User by email,
    write the new Google `sub` onto it, link the identities. Rejected
    because the bundle's "no account linking" out-of-scope decision
    was explicit; merging is a feature, not a side effect. Doing it
    silently also has a security smell: a Google user who happens to
    share an email with an email/password account would inherit
    access to that account without any consent step.
  - **Option C: throw, surface 500** — let the
    `DataIntegrityViolationException` from the `email UNIQUE`
    constraint bubble up. Rejected because the user did nothing
    wrong; a server-error page would be the wrong UX.
- *Why this one:* the soft fragment-error matches the rest of the
  OAuth UX. The frontend reads the fragment, shows a user-facing
  message ("This email is already registered with a password; please
  sign in with email/password instead."), and the user can retry on
  a different path. The User database is untouched. Account linking
  can be added later as a deliberate feature without unwinding any
  bad data.

**Decision: `passwordHash = null` for OAuth-only users, NOT an empty
string.**

- *What:* the OAuth path creates `User` rows with `passwordHash =
  null`. The IT
  `googleLogin_newUser_createsUserAndRedirectsWithToken` asserts
  this explicitly.
- *Alternatives considered:* `passwordHash = ""`. Rejected because
  `BCryptPasswordEncoder.encode("")` produces a valid-looking BCrypt
  output, and `BCryptPasswordEncoder.matches("", storedHash)` would
  then return true — the OAuth-only user would become loginable via
  `/api/auth/login` with an empty password. A phantom-loginable user
  is the textbook footgun the BCrypt API has.
- *Why this one:* null is the only value that short-circuits the
  matches path in `BCryptPasswordEncoder` (and in
  `AuthService.authenticate`'s upstream null check). It also makes
  the database state honest: "this user has no password" is exactly
  what the column should encode for an OAuth-only account.

**Decision: test the success handler in isolation, not the full OAuth
flow.**

- *What:* the IT constructs an `OAuth2AuthenticationToken` carrying a
  `DefaultOAuth2User` with hand-rolled attributes and invokes
  `OAuth2SuccessHandler.onAuthenticationSuccess` directly with mock
  servlet objects. We do NOT exercise the full flow against Google's
  authorization server.
- *Alternatives considered:*
  - **WireMock-style stub of `accounts.google.com`.** Rejected because
    the framework is what we'd be testing — the userinfo decoder, the
    token exchange, the redirect chain — none of which is code we own.
  - **`SecurityMockMvcRequestPostProcessors.oauth2Login()` against a
    `MockMvc` request.** A perfectly fine alternative, but for our
    use case we want to assert on the response from the handler
    directly (the redirect location, the User row state), and the
    direct-invocation shape is the most readable way to do that.
- *Why this one:* the success handler is the only piece of the flow
  we own; testing it in isolation keeps the coverage focused and
  fast. Spring Security is exhaustively tested upstream; we trust the
  framework boundary.

**Decision: no PII in OAuth failure logs.**

- *What:* the failure paths emit warnings with NO email, NO `sub`, NO
  display name. The messages are generic ("OAuth callback missing
  required profile attributes", "OAuth callback for email already
  registered to a different identity").
- *Alternatives considered:* log the email or `sub` for debugging.
  Rejected because OAuth failure paths can be triggered by a passing
  user (e.g. an automated probe), and a log that carries
  user-identifiable data needs the same protection as a user
  database. Keeping the logs PII-free removes that concern entirely.
- *Why this one:* defence in depth. The JWT (which DOES carry the
  user id) lives in the URL fragment in the success path, so it
  doesn't reach server logs either. Failures get fragment-error
  redirects (which carry no PII by design) and PII-free warnings.

## How this compares to what I know

- **In Scala / Typelevel this would be...** http4s with an OAuth2
  client library. `org.http4s.client.oauth2` provides the low-level
  OAuth2 plumbing; `silhouette` and `pac4j-scala` are the higher-
  level offerings, both with Google as a built-in provider. The
  shape of the success handler is the same — read the userinfo
  attributes, find-or-create the `User`, mint a JWT, redirect with
  the fragment. http4s gives you a more functional API for the
  redirect:
  ```scala
  def successHandler(profile: GoogleProfile): F[Response[F]] =
    for {
      user  <- userRepo.findOrCreateByGoogleSub(profile.sub, profile.email, profile.name)
      token <- jwtIssuer.issue(user)
      url   = Uri.unsafeFromString(s"$frontendBase/auth/callback#token=$token")
    } yield Response[F](status = Status.Found).putHeaders(Location(url))
  ```
  The structural difference: http4s composes effects with `flatMap`,
  while Spring's `AuthenticationSuccessHandler` is imperative. The
  end result is the same — a 302 with the right Location header.

  pac4j-scala's identity-collision story is similar to ours: by
  default it does NOT merge identities across providers; account
  linking is an explicit feature you opt into. silhouette's
  `IdentityService` has a `retrieve(LoginInfo)` and `save(User)`
  pair that maps cleanly to our `findByGoogleSub` / `save` pair.

- **In Node this would be...** Passport.js with
  `passport-google-oauth20`, or `@auth/core` (Auth.js — the
  framework-agnostic descendant of NextAuth):
  ```js
  passport.use(new GoogleStrategy(
    { clientID, clientSecret, callbackURL: '/login/oauth2/code/google' },
    async (accessToken, refreshToken, profile, done) => {
      let user = await db.users.findByGoogleSub(profile.id);
      if (!user) {
        const taken = await db.users.findByEmail(profile.emails[0].value);
        if (taken) return done(null, false, { message: 'email_taken' });
        user = await db.users.create({
          email: profile.emails[0].value,
          displayName: profile.displayName,
          passwordHash: null,
          googleSub: profile.id,
        });
      }
      done(null, user);
    },
  ));
  app.get('/oauth2/authorization/google',
    passport.authenticate('google', { scope: ['email', 'profile'] }));
  app.get('/login/oauth2/code/google',
    passport.authenticate('google', { session: false }),
    (req, res) => {
      const token = jwt.sign({ sub: req.user.id, email: req.user.email },
                             process.env.AUTH_JWT_SECRET, { expiresIn: '7d' });
      res.redirect(`${frontendBase}/auth/callback#token=${token}`);
    });
  ```
  The mapping is exact: Passport's strategy callback is the equivalent
  of our success handler — receive the profile, find-or-create the
  user, mint a JWT, redirect. The session setting (`session: false`)
  tells Passport not to put the user in an `HttpSession` — same as
  our `SessionCreationPolicy.STATELESS`.

  Auth.js has a higher-level abstraction (`signIn` and `signOut`
  endpoints, configurable callbacks) but the same redirect-with-token
  pattern works there too.

  The "redirect with fragment, not query" idiom is the same in all
  three ecosystems and is worth highlighting: a fragment is not sent
  to the server in subsequent requests, so even if an intermediate
  proxy logs full URLs, the token cannot leak into a backend's request
  log. Query parameters (`?token=…`) would appear in Caddy's access
  log, in nginx logs, in CloudWatch — the fragment hides them from
  all three. Anyone doing OAuth-into-an-SPA in any language should
  prefer the fragment over the query for the token transport.

## Gotchas / things I learned the hard way

- **Spring Security's OAuth2 client autoconfig binds the `client-id`
  to a non-empty string at boot.** If the production env var is unset
  and the YAML default is empty, the application starts and the OAuth
  flow is simply non-functional (Spring registers the provider but
  rejects authorization requests). It does NOT fail-fast at boot.
  That's why `AuthProperties.OAuthProps`'s compact constructor
  rejects a blank `frontend-redirect-base` — it gives us the
  fail-fast posture for the OAuth subtree even when Spring's binder
  is lenient on the registration values. The test profile provides
  fake values so context-load stays green; the operator is
  responsible for setting the real ones in production.
- **`DefaultOAuth2User` requires a non-empty authorities list.** The
  IT constructs the principal with `List.of(new
  SimpleGrantedAuthority("ROLE_USER"))` purely to satisfy the
  constructor; the handler ignores authorities (we have no role
  model). A `List.of()` argument would throw an
  `IllegalArgumentException` at IT setup time. The constraint comes
  from `DefaultOAuth2User`'s contract, not from Spring Security's
  policy.
- **The `name attribute key` argument to `DefaultOAuth2User`.** This
  is the attribute Spring Security uses to compute
  `OAuth2User.getName()` — Google's convention is `sub`. Passing the
  wrong key gives you a non-blocking warning at runtime and an
  unhelpful `getName()` return value; passing a key that's not in the
  attributes map throws at construction. The IT pins it to `"sub"`
  to match production.
- **`{baseUrl}` is a Spring Security template, not a Spring
  property-placeholder.** It looks like `${something}` but isn't.
  Spring Security's `DefaultRedirectStrategy` recognises it at
  request time and expands it from the current request's scheme +
  host + port. Hard-coding the production URL in the YAML would
  break local development; using Spring's `${}` placeholder syntax
  (`${myapp.base-url}/login/oauth2/code/google`) would force every
  deploy to know its own URL ahead of time. The template is the
  right level of abstraction.
- **The `oauth2Login` allow-list dance.** `/oauth2/**` and
  `/login/oauth2/**` MUST be reachable without auth. They are
  reached BEFORE the user is authenticated; if they required auth
  the flow would deadlock (Spring would try to authenticate the user
  to let them start the authentication flow). The
  reviewer's checklist explicitly verifies this — easy to forget
  because the rest of the security DSL reads "deny by default".

## To dig deeper

- [Spring Security reference — OAuth 2.0 Login](https://docs.spring.io/spring-security/reference/servlet/oauth2/login/index.html)
  — the canonical mental model for the `oauth2Login` DSL,
  `OAuth2AuthenticationToken`, `OAuth2User`, and the success/failure
  handler contract.
- [Spring Boot reference — OAuth2 Client](https://docs.spring.io/spring-boot/reference/web/spring-security.html#web.security.oauth2.client)
  — how the autoconfig binds `spring.security.oauth2.client.*` to
  `ClientRegistration` beans.
- [`CommonOAuth2Provider` source](https://github.com/spring-projects/spring-security/blob/main/config/src/main/java/org/springframework/security/config/oauth2/client/CommonOAuth2Provider.java)
  — the built-in providers (Google, GitHub, Facebook, Okta) and the
  endpoints they bake in.
- [Google Identity — OAuth 2.0 for Web Server Applications](https://developers.google.com/identity/protocols/oauth2/web-server)
  — the upstream protocol Google implements, with the userinfo
  attributes (`sub`, `email`, `email_verified`, `name`, `picture`,
  `locale`, ...) we can read from the `OAuth2User`.
- [OWASP Cheat Sheet — OAuth 2.0 Security](https://cheatsheetseries.owasp.org/cheatsheets/OAuth2_Security_Cheat_Sheet.html)
  — the threat model. State parameter, redirect URI validation,
  scope minimisation, and the "fragment vs query" choice for delivery
  to the client.
- [pac4j-scala](https://www.pac4j.org/) and [silhouette](https://www.silhouette.rocks/)
  — Scala parallels.
- [Auth.js (NextAuth's successor)](https://authjs.dev/) — the modern
  Node parallel; `@auth/core` is framework-agnostic and matches
  Spring Security's shape closely.

## Operator follow-ups

These are NOT acceptance items — they are real-world actions the
user must do outside the code to make production work:

- **Register a Google OAuth 2.0 Client** in Google Cloud Console:
  - Project: a free-tier dedicated project (or reuse an existing
    one if appropriate).
  - Application type: Web application.
  - Authorised redirect URIs:
    - `https://chess-backend.duckdns.org/login/oauth2/code/google`
    - `http://localhost:8080/login/oauth2/code/google`
  - Capture `client-id` and `client-secret`.
- **Set env vars in production:**
  - `GOOGLE_OAUTH_CLIENT_ID`
  - `GOOGLE_OAUTH_CLIENT_SECRET`
  - `AUTH_OAUTH_FRONTEND_REDIRECT_BASE=https://chess-frontend-52i.pages.dev`
- These get added to `/opt/chess/.env` on EC2 (see
  `docs/deploy-runbook.md`).

The feature is shippable to production only after these are done.

## File map

**Created:**

- `src/main/java/io/github/dariogguillen/chess/config/security/OAuth2SuccessHandler.java`
  — custom `AuthenticationSuccessHandler` that find-or-creates the
  `User` by Google `sub`, mints a JWT via `JwtIssuer`, and redirects
  to `${frontend-redirect-base}/auth/callback#token=<jwt>`. Three
  outcomes: success (token), email-taken (`#error=email_taken`),
  missing profile (`#error=oauth_missing_profile`). PII-free logging
  on every warning path.
- `src/test/java/io/github/dariogguillen/chess/config/security/OAuth2SuccessHandlerIT.java`
  — four-case IT: new user creates row + token works, existing
  googleSub reuses row, existing email with different sub does not
  merge, missing email surfaces error redirect.
- `notes/18-auth-google-oauth.md` — this note.

**Modified:**

- `pom.xml` — adds `spring-boot-starter-oauth2-client` with a
  multi-line comment explaining we are an OAuth2 *client*, not a
  resource server.
- `src/main/java/io/github/dariogguillen/chess/config/AuthProperties.java`
  — restructured from a flat `@ConfigurationProperties("auth.jwt")`
  record to a nested `@ConfigurationProperties("auth")` record with
  `Jwt jwt` and `OAuthProps oauth` sub-records. The feature-16/17
  API (`authProperties.secret()`, `authProperties.expirySeconds()`)
  is preserved via convenience accessors that delegate to the nested
  `Jwt`. Each nested record's compact constructor enforces non-blank
  invariants (HS256 secret ≥ 32 bytes; expiry > 0; frontend base
  non-blank).
- `src/main/java/io/github/dariogguillen/chess/config/SecurityConfig.java`
  — adds `.oauth2Login(oauth -> oauth.successHandler(oAuth2SuccessHandler))`
  to the filter chain, extends the anonymous allow-list with
  `/oauth2/**` and `/login/oauth2/**`, constructor-injects the new
  handler.
- `src/main/resources/application.yml` — adds the Google OAuth2
  client registration (`client-id`, `client-secret`, `scope`,
  `redirect-uri` template) plus `auth.oauth.frontend-redirect-base`.
- `src/test/resources/application-test.yml` — provides fake values
  for the OAuth client registration and the frontend redirect base
  so context-load stays green on every existing IT.
- `docs/architecture.md` — adds a "Google OAuth 2.0 sign-in (feature
  18)" subsection under "Authentication" with the flow Mermaid
  sequence diagram, the identity-collision policy, the
  `passwordHash = null` rationale, the URL-fragment rationale, the
  allow-list dance, and the operator setup steps.
- `README.md` — adds the Google sign-in option to the
  "Authentication (optional)" subsection; updates the static test
  count from 196 to 200.

**Cross-repo:** the new public surface (`/oauth2/authorization/google`
link, `/auth/callback#token=<jwt>` redirect contract) is additive —
no existing endpoint changes shape — so the backend ships
independently. The frontend's "Sign in with Google" button and the
`/auth/callback` route are separate features on the `chess-frontend`
repo, coordinated via the bundle's locked decisions in
`progress/current.md`.
