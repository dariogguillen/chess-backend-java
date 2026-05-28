# Current session — auth bundle (feature 19 in progress)

**Status:** in_progress on feature 19 (`auth-my-games`).
**Opened:** 2026-05-27 (continuing the bundle opened on the same day).
**Scope of feature 19:** protected `GET /api/me/games?page=&size=`
endpoint — the actual product benefit the user named: "con una cuenta
se pueden revisar las partidas jugadas". Game creation flow extended
so that when the request is authenticated, the new `games` row's
`white_user_id` / `black_user_id` columns are populated.

`feature_list.json` snapshot: **27 done, 1 in_progress, 1 pending.**

---

## Why this bundle (carried forward verbatim)

User goal (verbatim 2026-05-27): *"seria opcional, se puede seguir
juegando sin cuenta, pero con una cuenta se pueden revisar las
partidas jugadas por ejemplo"*.

Feature 19 is where that benefit lands. Features 16–18 built the
foundation, this feature lights it up.

---

## Bundle decomposition (features 16–20, carried forward)

| Priority | ID | Status | One-line goal |
| --- | --- | --- | --- |
| 16 | `auth-core` | done | User entity, Flyway V2, Spring Security base, JWT validation, `GET /api/me`. |
| 17 | `auth-jwt` | done | Email/password register + login → JWT (HS256). The JWT shape locked. |
| 18 | `auth-google-oauth` | done | Google OAuth 2.0 client; success handler redirects to frontend with JWT in URL fragment. |
| 19 | `auth-my-games` | **in_progress** | `GET /api/me/games` (paginated). Authenticated game creation links `games.{white,black}_user_id`. |
| 20 | `auth-stomp-trust` | pending | STOMP `ChannelInterceptor` validates JWT on CONNECT and prevents identity spoofing on SEND/SUBSCRIBE. |

Out-of-scope for the whole bundle:

- Refresh tokens. Email verification / password reset. 2FA.
- Account linking (Google + email/password in the same User).
- Claim flow for pre-existing anonymous games (fresh-start identity).

---

## Bundle-level technical decisions (carried forward verbatim)

1. **Token transport:** stateless JWT in `Authorization: Bearer`.
2. **JWT algorithm:** HS256 + `AUTH_JWT_SECRET`; 7-day lifetime; same `JwtIssuer` / `JwtVerifier` shared across features 17 and 18.
3. **JWT claims:** `sub` = `User.id`, `email`, `iat`, `exp`.
4. **OAuth callback:** backend redirect to frontend with token in URL fragment.
5. **CORS:** `allowCredentials` stays false.
6. **Identity linking:** fresh start. User-game link via `games.{white,black}_user_id` (FK columns added in feature 16's V2). No intermediate `players` table. `games.{white,black}_player_id` remain as audit-snapshot UUIDs.
7. **STOMP auth surface:** anonymous STOMP keeps working; JWT strengthens identity; spoofing blocked in feature 20.

---

## Feature 19 — `auth-my-games` — detailed plan

### Approach

This feature has two surfaces that must move together:

1. **Read surface — the new endpoint.** `GET /api/me/games?page=&size=`
   returns the authenticated user's archived games (terminal status,
   newest first), paginated. Auth-required (Bearer JWT). Returns
   401 without auth. This is a separate endpoint from the existing
   guest-friendly `GET /api/players/{id}/games` — that one stays
   open and unchanged, filtering by `player_id`; the new one filters
   by `user_id`.

2. **Write surface — populating the FK columns.** When a game is
   archived (terminal status), `GameEntity` must persist
   `white_user_id` / `black_user_id` (the FK columns feature 16
   added) whenever the corresponding side was an authenticated user.
   For guest games (no auth at creation), both stay null. This
   requires threading `user_id` from the security context at game
   creation → through the `Player` domain record → into the
   active-state representation in Redis → out to the archive write.

The `Player` domain record gains a nullable `UUID userId` field.
The compact constructor accepts null. JSON wire-format leakage is
checked: `GameStateResponse` and any other DTO that exposes player
data must NOT include `Player.userId` (the response shape stays
backwards-compatible).

### Files created or modified, by package

**`io.github.dariogguillen.chess.domain`** (1 modified)
- `Player.java` — modified. Add nullable `UUID userId` field.
  Compact constructor accepts null (no `Objects.requireNonNull`
  on userId; the existing checks on `id` and `displayName` stay).
  JavaDoc updated to explain the new field and that `null` means
  "guest / unauthenticated".

**`io.github.dariogguillen.chess.web.game`** (1 modified)
- `GameStateResponse.java` (or whatever the game-state wire DTO
  is) — confirm it does NOT include `Player.userId`. If the
  current implementation embeds `Player` raw, the implementer
  refactors to a dedicated `PlayerView(id, displayName)` record
  for the wire — this guarantees zero userId leakage even if a
  future field is added to `Player`. Trade-off: small refactor;
  payoff: contract isolation. The implementer makes the call;
  document in the feature note.

**`io.github.dariogguillen.chess.service`** (1+ modified)
- `GameService.java` and / or `RoomService.java` (wherever
  Players are constructed during game creation) — modified to
  accept an optional `UUID currentUserId` parameter. When
  present, the new `Player` carries it; when null, guest path.
  This is a thin pass-through — service does not read the
  security context itself.
- `GameHistoryService.java` — modified. New method
  `Page<ArchivedGamePlayerView> findByUser(UUID userId, Pageable pageable)`.
  The existing `findByPlayer(UUID playerId)` stays as-is.

**`io.github.dariogguillen.chess.web.game`** (1+ modified)
- `GameController.java` and / or `RoomController.java` (whichever
  is the game-creation entrypoint) — modified. Read
  `currentUserId` from the security context via
  `@AuthenticationPrincipal` or `SecurityContextHolder`. Anonymous
  request → null. Authenticated → the User's UUID. Pass it to
  the service.

**`io.github.dariogguillen.chess.web.me`** (new package)
- `MyGamesController.java` — new. `@RestController` at
  `/api/me/games`. `@Tag(name = "My account", description = ...)`
  (or reuse the `Authentication` tag — implementer's call).
  `GET` method:
  - Reads pagination via `@RequestParam(defaultValue = "0") int page`
    and `@RequestParam(defaultValue = "20") int size`. Validation
    via `@Min(0)` on page and `@Min(1) @Max(100)` on size, so
    out-of-bound values surface as `VALIDATION_FAILED` 400.
  - Reads `currentUser` from the security context. By the time
    the controller runs, the user is guaranteed authenticated
    (the SecurityFilterChain would have 401'd otherwise).
  - Calls `gameHistoryService.findByUser(currentUser.getId(),
    PageRequest.of(page, size))`.
  - Returns `MyGamesPage` (record) or Spring's `Page<MyGameSummary>`
    directly. Implementer chooses; the JSON shape MUST include
    `content`, `totalElements`, `totalPages`, `size`, `number`
    (the standard Spring Data fields) so the frontend uses a
    well-known shape.
  - Springdoc: `@Operation`, `@ApiResponse(200)` linked to the
    page schema, `@ApiResponse(400)` for validation,
    `@ApiResponse(401)` for missing JWT linked to `ErrorResponse`
    (consistent with `MeController`).
- `MyGameSummary.java` — new. Record:
  `(UUID gameId, String roomId, String opponentDisplayName,
  Side selfSide, GameStatus status, Instant endedAt, int moveCount)`.
  Mirrors `PlayerGameSummary` but driven by user-id mapping
  (whichever side's `user_id` matches the authenticated user is
  "self"; the other side is "opponent"; the display name comes
  from the audit-snapshot `*_display_name` column).

**`io.github.dariogguillen.chess.persistence`** (3 modified, 1 possibly new)
- `GameEntity.java` — modified. Add two new fields:
  `whiteUserId UUID NULL` and `blackUserId UUID NULL` mapped to
  the existing migration columns `white_user_id` and
  `black_user_id` (created by V2 in feature 16). Both nullable.
  `@Column(name = "white_user_id")` / `@Column(name = "black_user_id")`.
- `GameEntityMapper.java` — modified. When converting a
  terminated `Game` to a new `GameEntity` (archive path), populate
  `whiteUserId` from `game.white().userId()` and `blackUserId`
  from `game.black().userId()`. Both null-tolerant.
- `GameHistoryRepository.java` — modified. New method:
  ```java
  @Query("SELECT new ArchivedGamePlayerView(...) FROM GameEntity g
         WHERE g.whiteUserId = :userId OR g.blackUserId = :userId
         ORDER BY g.endedAt DESC")
  Page<ArchivedGamePlayerView> findByUserId(
      @Param("userId") UUID userId, Pageable pageable);
  ```
  Reuses the existing `ArchivedGamePlayerView` projection — no new
  projection class needed unless the implementer wants to surface
  user-side info, in which case extending the projection is fine.
- `ArchivedGamePlayerView.java` — likely no change. The projection
  carries enough fields for the controller to determine self-side
  by comparing `whiteUserId == currentUser.getId()`. **However**:
  the existing projection might NOT carry `whiteUserId` /
  `blackUserId` (it was designed pre-feature-16). The implementer
  may need to add those fields to the projection so the controller
  knows which side is self. Decide in implementation; document.

**`src/test/java/.../web/me/MyGamesIT.java`** (new IT)

Cases:

1. `getMyGames_withoutAuth_returns401WithAuthenticationRequired` —
   no Bearer → 401 + `ErrorResponse(AUTHENTICATION_REQUIRED, ...)`.
2. `getMyGames_emptyHistory_returns200WithEmptyPage` — newly
   registered user, no games → 200 with `content: []`,
   `totalElements: 0`.
3. `getMyGames_userHasArchivedGames_returnsOnlyOwn` — register two
   users A and B; create archived games for each; A's call to
   `/api/me/games` returns only A's games (count + ids confirmed),
   B's games are NOT included.
4. `getMyGames_anonymousArchivedGames_notVisible` — create an
   anonymous archived game (X-Player-Id only, no auth); register
   a user A with the same email NOT used by the guest; A's call
   returns empty (fresh-start identity confirmed end-to-end).
5. `getMyGames_pagination_respectsPageAndSize` — create 5 archived
   games for user A; request `?page=0&size=2` returns 2 entries +
   `totalElements: 5`, `totalPages: 3`; request `?page=2&size=2`
   returns 1 entry.
6. `getMyGames_invalidPagination_returns400` — `?size=101`
   (over max) and `?page=-1` (under min) both return 400 with
   `VALIDATION_FAILED`.
7. `getMyGames_authenticatedGameCreation_populatesUserIdColumns` —
   register user A; create a game where A is white via the auth'd
   path; archive the game (or assert mid-create); inspect the
   `GameEntity` (or the JSON response of `/api/me/games`) to
   confirm `white_user_id = A.id` and `black_user_id = null` (or
   the other user's id if B is also auth'd).

A missing case = `[FAIL]`.

**Modified ITs (regression):**
- `GameIT` / `RoomIT` (existing) — confirm anonymous flow stays
  green WITHOUT modification: `POST /api/games` without
  Authorization still works; archived rows have
  `white_user_id = black_user_id = null`. Implementer must not
  edit these tests; reviewer verifies via `git diff --name-only`.
- `PlayerGamesIT` (if it exists) — `GET /api/players/{id}/games`
  still works for guests, still hard-capped at 50. Unchanged.

**Docs:**
- `docs/architecture.md` — "API contract" section gains the new
  `/api/me/games` endpoint. Authentication section gains a note
  that the data model wired in feature 16 (FK columns on `games`)
  is now active.
- `README.md` — API section gains a bullet for the new endpoint;
  static test-count claim bumped 200 → expected ~207 (97 unit +
  110 IT = 207; +7 IT from this feature).

**`notes/19-auth-my-games.md`** (new)
- Follows `_template.md`. Java/Spring concepts: Spring Data
  `Page<T>` + `Pageable` + JPQL pagination; `@AuthenticationPrincipal`
  vs `SecurityContextHolder.getContext().getAuthentication()`; how
  the security filter chain guarantees the controller-time
  invariant "authenticated user is present"; Jackson record
  deserialization backwards-compatibility for the `Player.userId`
  field arriving from Redis state created before this feature.
- Cross-ecosystem: `doobie` + `fs2` pagination via `LIMIT/OFFSET`;
  http4s `AuthMiddleware`-passed principals; Scala `cats.data.NonEmptyList`
  vs Spring `Page<T>` ergonomics.
- Decisions: (a) self-side determination from user-id comparison
  vs storing self-side in the projection; (b) view-DTO refactor on
  `GameStateResponse` if leakage was at risk; (c) keep
  `GET /api/players/{id}/games` unchanged for guests.
- Gotchas: Jackson record deserialization with a new nullable
  field (existing Redis state may be a pre-feature-19 shape; the
  field MUST default to null cleanly, not throw); JPQL `Page<T>`
  needs a count query — Spring derives it automatically for
  simple queries but the implementer should verify by reading the
  Failsafe logs for "executing count query".

### Verification

`./init.sh` is the canonical gate. New IT count: 7
(`MyGamesIT`). Expected new total: 207 (97 unit + 110 IT).
Delta from feature 18's 200: +7 IT, 0 unit.

Reviewer's extra checks:
- `Player.userId` does NOT appear in any JSON wire-format
  response (`GET /api/games/{id}`, `GET /api/players/{id}/games`,
  STOMP `MoveEvent` / `RoomEvent` payloads). Grep:
  `curl -s http://localhost:8080/v3/api-docs | jq` should show no
  `userId` on any player-shaped schema. If `GameStateResponse`
  embedded `Player` raw, the implementer's refactor must
  guarantee this.
- `games.white_user_id` / `games.black_user_id` are populated on
  archive when the side was authenticated, null when guest. The
  IT case `getMyGames_authenticatedGameCreation_populatesUserIdColumns`
  pins this; the reviewer can cross-check by inspecting the
  `GameEntity` field after a regression-IT archive completes.
- The new endpoint does NOT accidentally widen the OPEN allow-list:
  `/api/me/games` is `.authenticated()` (no `permitAll`
  match), and the existing `/api/players/{id}/games` is still
  reachable without auth.
- The `Pageable` request param binding works: a request without
  `page` / `size` query params returns the default first-page-20.
- Existing 200 ITs stay green without modification (regression
  via `git diff --name-only src/test`).

### Cross-repo coordination

**Required.** The new endpoint `/api/me/games` is consumed by the
frontend's "my games" view. The pagination JSON shape is the
standard Spring Data envelope (`content`, `totalElements`,
`totalPages`, `size`, `number`), pre-known to the frontend's
typed client codegen via `/v3/api-docs`. The existing
`/api/players/{id}/games` stays as-is — the frontend can switch
between the two endpoints based on auth state. The change is
additive.

### Java / Spring concepts to highlight in the feature note

- Spring Data `Pageable` + `Page<T>` + JPQL pagination; the
  derived count query; the `PageRequest.of(page, size)` builder.
- `@AuthenticationPrincipal` annotation for injecting the
  current user directly into a controller method signature; how
  Spring resolves it against the `SecurityContextHolder`.
- Jackson record deserialization rules: how a record with a new
  nullable field handles old JSON shapes that lack the field
  (`@JsonInclude` / `@JsonCreator` defaults).
- The active/archive split: Redis holds `Game` while active,
  Postgres `games` table holds it terminal. The auth thread (a
  nullable `UUID userId` on `Player`) must survive the
  serialise/deserialise cycle through Redis without breaking
  pre-feature-19 in-flight games at deploy time.
- Spring's standard pagination JSON envelope — what fields it
  emits and how a TypeScript client typically consumes it.

### What feature 19 does NOT do

- No "claim my anonymous games" flow (out-of-scope at bundle
  level).
- No bulk delete / archive management.
- No filtering / sorting beyond default `endedAt DESC`.
- No "in-progress games" listing (those are visible via
  `/api/games/{id}` if you know the id; not in scope here).
- No replay endpoint changes (feature 19 of the architecture
  doc lists this as a future candidate).

---

## Carried over from 2026-05-25 closure (operator follow-ups)

- **Rotate the RDS master password.**
- **Configure branch protection on `main`.**
- **Replace the static test-count claim** in `README.md` with a
  dynamic count or remove it. Will need bumping again this
  feature (200 → ~207).

These are operator actions — not feature 19 acceptance items.

---

## Leader notes for the next handoffs

- Feature 19 plan needs user approval before delegation.
- When feature 19 closes, the bundle has one feature left
  (`auth-stomp-trust`). `current.md` will be rewritten with the
  feature-20 detail; bundle decomposition + decisions are
  copy-forwarded one last time.
- Per [[feedback-flag-untracked-files-at-close]]: at feature 19
  close, flag the new package `web/me/` (likely `MyGamesController.java`
  + `MyGameSummary.java`) and the new test class.
