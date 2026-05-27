# Current session — auth bundle (feature 18 in progress)

**Status:** in_progress on feature 18 (`auth-google-oauth`).
**Opened:** 2026-05-27 (continuing the bundle opened on the same day).
**Scope of feature 18:** Google OAuth 2.0 sign-in. Spring Security
OAuth2 client + Google provider. Success handler issues a JWT using
the same shape locked in feature 17 and redirects the browser to the
frontend's `/auth/callback#token=<jwt>` URL.

`feature_list.json` snapshot: **26 done, 1 in_progress, 2 pending.**

---

## Why this bundle (carried forward verbatim)

User goal (verbatim 2026-05-27): *"seria opcional, se puede seguir
juegando sin cuenta, pero con una cuenta se pueden revisar las
partidas jugadas por ejemplo"*.

The auth surface is the single highest-signal addition we can ship to
a senior backend portfolio: it touches Spring Security, JPA, CORS,
JWT cryptography, OAuth2 client integration, and STOMP message
interception — concepts a recruiter expects to see exercised on a
backend with a real frontend and a real production deploy.

---

## Bundle decomposition (features 16–20, carried forward)

| Priority | ID | Status | One-line goal |
| --- | --- | --- | --- |
| 16 | `auth-core` | done | User entity, Flyway V2, Spring Security base, JWT validation, `GET /api/me`. |
| 17 | `auth-jwt` | done | Email/password register + login → JWT (HS256). The JWT shape is locked. |
| 18 | `auth-google-oauth` | **in_progress** | Google OAuth 2.0 client; success handler redirects to frontend with JWT in URL fragment. |
| 19 | `auth-my-games` | pending | `GET /api/me/games` (paginated). Authenticated game creation links `games.{white,black}_user_id`. |
| 20 | `auth-stomp-trust` | pending | STOMP `ChannelInterceptor` validates JWT on CONNECT and prevents identity spoofing on SEND/SUBSCRIBE. |

Out-of-scope for the whole bundle (decided 2026-05-27 with user):

- Refresh tokens (single JWT, 7-day expiry).
- Email verification / password reset / magic links.
- 2FA / TOTP.
- Account linking (Google + email/password in the same User).
- Claim flow for pre-existing anonymous games (fresh-start identity
  model: anonymous games created before login stay anonymous forever).

---

## Bundle-level technical decisions (carried forward verbatim)

These were answered once at the bundle level so each feature plan can
stay narrow. Reviewers cross-reference here, not in every feature plan.

1. **Token transport:** stateless JWT in `Authorization: Bearer <token>`
   header. No session cookies. Confirmed with user 2026-05-27.
2. **JWT algorithm:** HS256 with a shared secret in env var
   `AUTH_JWT_SECRET`. Symmetric is fine because issuer = verifier
   (same backend). Token lifetime: 7 days. Locked by feature 16's
   `JwtVerifier`; feature 17 added `JwtIssuer`. **Feature 18 reuses
   `JwtIssuer` byte-identically.**
3. **JWT claims:** `sub` = `User.id` (UUID string), `email`,
   `iat`, `exp`. No roles / authorities yet. Locked in feature 17;
   feature 18 produces identical claims regardless of the auth path
   (email/password or Google OAuth).
4. **OAuth callback delivery:** backend redirect to
   `${auth.oauth.frontend-redirect-base}/auth/callback#token=<jwt>`.
   Frontend reads fragment, stores token, clears with
   `history.replaceState`. Confirmed with user 2026-05-27.
5. **CORS:** `allowCredentials` stays **false**. The OAuth flow uses
   top-level browser redirects, not XHR — no CORS impact. The
   `Authorization` header is already on the allow-list from
   feature 16.
6. **Identity linking:** fresh start. `User.id` is the canonical
   identity; user-game link via `games.{white,black}_user_id`. No
   intermediate `players` table.
7. **STOMP auth surface:** anonymous STOMP keeps working; JWT
   strengthens identity, never gates access. Spoofing blocked in
   feature 20.

---

## Feature 18 — `auth-google-oauth` — detailed plan

### Approach

Spring Security has first-class OAuth2 client support — adding the
starter, registering Google as a provider in `application.yml`, and
configuring `.oauth2Login()` in the `SecurityFilterChain` gets us
~80% of the way. The remaining 20% is the success handler: take the
`OAuth2User` Spring builds from Google's userinfo response, find or
create our `User` by `googleSub`, mint a JWT via the existing
`JwtIssuer` from feature 17, and redirect to the frontend with the
token in the URL fragment.

The flow end-to-end:

1. User clicks "Login with Google" on the frontend → frontend
   navigates to `https://chess-backend.duckdns.org/oauth2/authorization/google`.
2. Spring Security redirects to Google with the OAuth2 authz request.
3. Google authenticates the user, asks for consent, redirects back
   to `https://chess-backend.duckdns.org/login/oauth2/code/google?code=…`.
4. Spring Security exchanges the code for tokens, fetches userinfo,
   builds an `OAuth2User` principal, and invokes the configured
   success handler.
5. Our custom `OAuth2SuccessHandler` extracts `sub` (Google's
   stable user id), `email`, and `name` from the `OAuth2User`,
   calls `UserRepository.findByGoogleSub` to find-or-create the
   `User`, issues a JWT via `JwtIssuer`, and writes a
   `302 Location: ${frontend-redirect-base}/auth/callback#token=<jwt>`
   to the response.
6. Frontend's `/auth/callback` route reads `window.location.hash`,
   parses `token=…`, calls `history.replaceState` to clean the URL,
   and stores the token (the frontend's auth UI feature).

### Files created or modified, by package

**`pom.xml`** (modified)
- Add `spring-boot-starter-oauth2-client`. Inline comment justifies
  it (Google OAuth 2.0 client + userinfo fetch + token exchange,
  all wired by Spring Security's standard config). No `oauth2-resource-server`
  starter — we are an OAuth2 client, not a resource server; our
  bearer-token validation is the custom feature-16 `JwtAuthenticationFilter`.

**`io.github.dariogguillen.chess.config`** (1 modified)
- `SecurityConfig.java` — modified.
  1. New `oauth2Login` block in the `SecurityFilterChain` bean:
     `.oauth2Login(oauth -> oauth.successHandler(oAuth2SuccessHandler))`.
     The default `/oauth2/authorization/google` and
     `/login/oauth2/code/google` endpoints are auto-registered by
     Spring Security.
  2. Anonymous allow-list gains `/oauth2/**` and `/login/oauth2/**`
     (the user is not authenticated yet during the OAuth dance —
     these endpoints must be reachable before the JWT exists).
  3. `OAuth2SuccessHandler` constructor-injected.
- `AuthProperties.java` — modified. Adds nested record
  `OAuthProps(String frontendRedirectBase)` bound under
  `auth.oauth`. Compact constructor enforces non-blank.

**`io.github.dariogguillen.chess.config.security`** (1 new)
- `OAuth2SuccessHandler.java` — new. Implements
  `AuthenticationSuccessHandler`. Constructor-injected:
  `UserRepository`, `JwtIssuer`, `AuthProperties`, `Clock`. On
  success:
  1. Cast `Authentication` to `OAuth2AuthenticationToken`; extract
     `OAuth2User`.
  2. Read `sub`, `email`, `name` attributes. Defensive: if
     `email` or `sub` is null/blank, log a warning and redirect
     to `${frontend-redirect-base}/auth/callback#error=oauth_missing_profile`
     (frontend handles the error).
  3. `userRepository.findByGoogleSub(sub)` — if present, use that
     `User`; if not, create a new `User` with the Google profile
     data and `passwordHash = null` (cannot log in via
     email/password without explicit password set — that's the
     "no account linking" out-of-scope).
  4. `jwtIssuer.issue(user)` produces the JWT.
  5. Redirect to `${frontend-redirect-base}/auth/callback#token=<jwt>`
     using `DefaultRedirectStrategy`.

**`src/main/resources/application.yml`** (modified)
- Add `spring.security.oauth2.client.registration.google` block:
  - `client-id: ${GOOGLE_OAUTH_CLIENT_ID:}` (fail-fast prod; empty
    in test profile).
  - `client-secret: ${GOOGLE_OAUTH_CLIENT_SECRET:}`.
  - `scope: openid, email, profile`.
  - `redirect-uri: '{baseUrl}/login/oauth2/code/google'` (Spring's
    template; resolves to backend URL at runtime — works for both
    localhost and production).
- Add `auth.oauth.frontend-redirect-base: ${AUTH_OAUTH_FRONTEND_REDIRECT_BASE:http://localhost:5173}`.
  Dev/local default is the Vite dev server; production
  override via env var to `https://chess-frontend-52i.pages.dev`.
- No `provider.google` block needed — Spring Security has Google
  as a built-in `CommonOAuth2Provider` constant with the standard
  endpoints baked in.

**`src/test/resources/application-test.yml`** (modified)
- Provides fake `client-id` and `client-secret` so the test profile
  boots; the actual OAuth flow is never exercised against Google in
  tests. Sets `auth.oauth.frontend-redirect-base: http://localhost:5173`
  (or whatever the IT prefers).

**`src/test/java/.../config/security/OAuth2SuccessHandlerIT.java`**
(new IT)
- Cases:
  1. `googleLogin_newUser_createsUserAndRedirectsWithToken` —
     builds an `OAuth2AuthenticationToken` with an `OAuth2User`
     carrying a fresh `sub`, `email`, `name`; invokes the success
     handler directly via the Spring MVC test infrastructure;
     asserts:
     - `User` row created with the given `googleSub`, `email`,
       `displayName`; `passwordHash` is null.
     - Response status 302.
     - `Location` header starts with the configured frontend
       base + `/auth/callback#token=`.
     - The token after `#token=` is accepted by feature-16's
       `JwtAuthenticationFilter` (call `/api/me` with it →
       200 with the new user's payload).
  2. `googleLogin_existingUserByGoogleSub_reusesUser` —
     pre-insert a `User` with a known `googleSub`; trigger the
     handler with the same `sub`; assert no new row created
     (count unchanged) AND the redirect token resolves to the
     pre-existing user.
  3. `googleLogin_existingEmailDifferentGoogleSub_doesNotMerge` —
     pre-insert a User with `email = "x@example.com"` and
     `googleSub = null` (email/password user from feature 17);
     trigger the OAuth flow with the same email but a different
     `sub`; assert behaviour matches the "no account linking"
     out-of-scope decision. Two options the implementer must
     choose between:
     - **Option A:** create a NEW User with the Google `sub`,
       allowing two users with the same email (violates email
       UNIQUE — DB will throw, so this is actually impossible
       given the migration).
     - **Option B:** treat this as a registration conflict;
       redirect to `…/auth/callback#error=email_taken`. The
     **leader's call: Option B.** The bundle's "no account linking"
     out-of-scope decision means we do not merge identities; the
     cleanest user-facing outcome is to surface the conflict
     rather than silently fail at the DB level. The IT asserts
     the error redirect; the User row count is unchanged.
  4. `googleLogin_missingEmail_redirectsWithError` — builds an
     `OAuth2User` with `sub` present but `email` null/blank
     (defensive); asserts the redirect goes to
     `…/auth/callback#error=oauth_missing_profile`.

The implementer should NOT attempt to test the full OAuth flow
end-to-end (request to Google, code exchange, userinfo fetch).
Those are Spring Security's responsibility; we trust the framework.
Our coverage is the success handler in isolation.

**`docs/architecture.md`** (modified)
- Extend "Authentication" section with a new subsection:
  - The OAuth flow diagram (a Mermaid `sequenceDiagram` showing
    Frontend → Backend → Google → Backend → Frontend with the
    redirect-with-fragment as the final step).
  - Note that the OAuth and email/password paths converge on the
    same `User` aggregate, the same JWT shape, and the same
    `JwtAuthenticationFilter`.
  - "Operator setup" subsection documenting the Google Cloud
    Console registration: create OAuth 2.0 Client ID (Web
    application), set the authorised redirect URIs to
    `https://chess-backend.duckdns.org/login/oauth2/code/google`
    AND `http://localhost:8080/login/oauth2/code/google`, capture
    `client-id` and `client-secret` into AWS env vars
    `GOOGLE_OAUTH_CLIENT_ID` / `GOOGLE_OAUTH_CLIENT_SECRET`.

**`README.md`** (modified)
- "Authentication (optional)" subsection adds a "or sign in with
  Google" bullet pointing at `/oauth2/authorization/google`.
- Static test-count claim bumped (current: 196 → expected ~200,
  see Verification).

**`notes/18-auth-google-oauth.md`** (new)
- Follows `_template.md`. Cross-ecosystem section covers:
  - http4s `Authentication` + OAuth2 with `org.http4s.client.oauth2`.
  - Scala `pac4j-scala` and `silhouette` for OAuth2 client work.
  - Node parallels: `passport-google-oauth20`, `@auth/core` (Auth.js).
  - The "redirect with fragment, not query" trick (fragment is not
    sent to the server, so the token does not leak into backend
    logs even if some intermediate proxy logs the URL).

### Verification

`./init.sh` is the canonical gate. New ITs (`OAuth2SuccessHandlerIT`)
add 4 IT cases; expected new total: 200 (97 unit + 103 IT). Delta
from feature 17's 196: +4 IT, 0 unit.

Reviewer's extra checks:
- The success handler is wired in `SecurityConfig` AND the
  default Spring auto-config is not overridden in a way that
  silently disables it.
- `User` rows minted by the OAuth path have `passwordHash = null`,
  not an empty string (BCrypt would happily hash an empty string,
  creating a phantom-loginable user).
- The JWT minted by the OAuth path is byte-for-byte interchangeable
  with one minted by `/api/auth/login` (same claims shape, same
  signing key). The IT round-trip case asserts this implicitly by
  calling `/api/me`.
- `application-test.yml` provides client-id / client-secret values
  so the context loads even though the OAuth flow is never
  triggered in tests; if missing, every IT will fail at context
  startup with a Spring binding error.
- The `oauth2Login` config block does NOT accidentally widen the
  allow-list — `.authenticated()` rule for `/api/me` must still
  apply (the OAuth dance does NOT require `/api/me` to be public).

### Cross-repo coordination

**Required.** This feature adds a public surface the frontend will
consume (`/oauth2/authorization/google` link, `/auth/callback`
fragment handler). The change is **additive** — no existing endpoint
changes shape — so the backend ships independently. The frontend's
"Sign in with Google" button is a separate feature on the
`chess-frontend` repo coordinated via the bundle's locked decisions
in this file.

### Operator follow-ups this feature adds

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
  - `AUTH_OAUTH_FRONTEND_REDIRECT_BASE` =
    `https://chess-frontend-52i.pages.dev`
- These get added to `/opt/chess/.env` on EC2 (see
  `docs/deploy-runbook.md`).

The feature is shippable to production only after these are done.
The implementer documents them in `notes/18-auth-google-oauth.md`
"Operator follow-ups" and in `docs/architecture.md` "Operator setup".

### Java / Spring concepts to highlight in the feature note

- Spring Security's OAuth2 client surface: `oauth2Login` DSL,
  `CommonOAuth2Provider` constants for the major providers,
  `OAuth2AuthenticationToken`, `OAuth2User`.
- `AuthenticationSuccessHandler` vs the default behaviour (which
  redirects to a saved-request URI or `/`). Why we need a custom
  handler at all.
- The "redirect with URL fragment" pattern: fragments are not sent
  to the server in subsequent requests, so the token cannot leak
  into server logs even if an intermediate proxy logs the URL.
  Different from query parameters.
- `find-or-create` on `googleSub` as the canonical pattern for
  social-login first-time sign-in.
- Why `passwordHash = null` is correct for OAuth-only users (and
  why an empty string would be a footgun).
- Spring's `{baseUrl}` template in `redirect-uri` config and how
  it resolves at runtime to the actual host.

### What feature 18 does NOT do

- No account linking (Google + email/password in the same User).
  Email collision between paths is surfaced as an error to the
  frontend.
- No multi-provider OAuth (no Apple, no GitHub). Adding more
  providers later is `.clientRegistration(...)` per provider —
  the bundle's scope is Google only.
- No "login with refreshable Google access token" — we use Google
  to authenticate ONCE and then issue our own JWT. The Google
  access token is discarded after the userinfo fetch.

---

## Carried over from 2026-05-25 closure (operator follow-ups)

These still apply:

- **Rotate the RDS master password.** Pre-existing, increasingly
  pressing as the auth surface grows.
- **Configure branch protection on `main`.** Pre-existing.
- **Replace the static test-count claim in `README.md`** with a
  dynamic count or remove it. Partially closed by feature 17
  (181 → 196); will need to update again to ~200 in this feature.

---

## Leader notes for the next handoffs

- Feature 18 plan needs user approval per `leader.md` before the
  implementer handoff. The leader is waiting on the user's
  explicit OK to delegate.
- When feature 18 closes, this file gets replaced with feature 19's
  detailed plan; "Bundle decomposition" + "Bundle-level technical
  decisions" sections above get copied forward verbatim again.
- Per [[feedback-flag-untracked-files-at-close]]: at feature 18
  close, flag the new file `OAuth2SuccessHandler.java` and any new
  test file. No new package this feature (re-uses `config/security/`
  and `test/config/security/`).
