## Feature 15 — Swap CORS production origin to Cloudflare Pages

**Feature ID:** `cors-cloudflare-origin` (from `feature_list.json`)

**Status:** in progress

---

## What we built

The backend's CORS allow-list no longer references GitHub Pages.
The frontend was migrated from `https://dariogguillen.github.io` to
`https://chess-frontend-52i.pages.dev` (Cloudflare Pages); this
feature follows that move by swapping the production origin in the
`chess.cors.allowed-origin-patterns` default, in the IT that locks
the policy in, and in the two doc surfaces that name the origin
(`docs/architecture.md` and `README.md`). The localhost-wildcard
dev entry and the env-var override mechanism stay intact.

## Java / Spring concepts that appear

- **The single-source-of-truth dividend from feature 10.** Because
  feature 10 wired both REST (`CorsConfig`) and STOMP
  (`WebSocketConfig`) through the same `CorsProperties` record, this
  origin migration was a one-line edit in `application.yml` plus the
  test updates. The drift surface a two-hardcoded-lists shape would
  have exposed (REST allowing Cloudflare while STOMP still allows
  github.io, or vice-versa) simply does not exist here — both layers
  read from the same bound list, so they migrate atomically. This
  is the concrete payoff of the architectural decision made in
  feature 10: an operational change that would have been a
  multi-file audit becomes a property-default swap.
- **`@ConfigurationProperties` + env-var override pattern.**
  `application.yml` declares
  `allowed-origin-patterns: ${CHESS_CORS_ALLOWED_ORIGIN_PATTERNS:<default>}`,
  which is Spring's relaxed-binding shape for "use the env var if
  set, otherwise use the literal default". This feature touches the
  default only. Production behaviour after the deploy depends on
  whether the EC2 host has `CHESS_CORS_ALLOWED_ORIGIN_PATTERNS` set
  in `/opt/chess/.env`: if it does, the new default is shadowed and
  the operator must update the env file too. If it does not, the
  new default takes effect at the next container restart. This is
  the standard pattern from
  [Spring Boot externalised configuration](https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.typesafe-configuration-properties)
  — the env var is the operator escape hatch, the YAML default is
  what ships in the artifact.
- **Naming as documentation.** The IT constant was
  `GITHUB_PAGES_ORIGIN`. Keeping that name with the new Cloudflare
  value would silently lie about what the value represents and
  seed confusion for the next reader who greps for `GITHUB_PAGES`
  expecting a github.io URL and finds a `pages.dev` one. The fix
  is the rename to `CLOUDFLARE_PAGES_ORIGIN` plus a matching rename
  of the test method (`preflight_allowedOriginGithubPages_…` →
  `preflight_allowedOriginCloudflarePages_…`). The cost is one
  rename; the cost of skipping the rename compounds every time a
  future reader touches the file. Same discipline as the rest of
  the codebase: identifiers carry meaning, and stale identifiers
  cost more than they save.

## Decisions taken

**Replace the github.io origin entirely, not add Cloudflare alongside.**

- Decision: the allow-list goes from
  `https://dariogguillen.github.io,http://localhost:*` to
  `https://chess-frontend-52i.pages.dev,http://localhost:*`. The
  github.io entry is removed, not kept "for compatibility".
- Alternatives: keep both origins listed during a transition window.
- Why: the frontend has already moved; the github.io deploy is no
  longer the production URL. Keeping a stale origin in the
  allow-list would advertise a capability that no longer exists and
  seed confusion when a future reader asks "wait, do we still
  publish to github.io?". The allow-list is a statement about what
  we support, not a museum of what we used to support.

**Treat a one-line config change as a full harness cycle.**

- Decision: this is a feature in `feature_list.json` with priority
  15, a plan in `progress/current.md`, an IT update, a doc update,
  and a feature note.
- Alternatives: an informal PR with just the YAML edit, since the
  behavioural change is one line and the IT update is mechanical.
- Why: precedent from feature 11.7 (`cors-x-player-id`), which was
  also a one-line CORS change and went through the same harness
  cycle. The point of the harness is consistency — the rule that
  "all behavioural changes go through the leader/implementer/
  reviewer loop" loses its meaning the moment we start carving out
  exceptions for "small enough" edits. The harness cost is fixed;
  the discipline payoff is proportional to how unwaveringly the
  rule is applied.

## How this compares to what I know

- **In Scala / Typelevel this would be...** the exact same shape
  with `pureconfig`-derived config records. You'd declare
  `case class CorsConfig(allowedOriginPatterns: NonEmptyList[String])`,
  load it once at the edge of the world with
  `ConfigSource.default.loadOrThrow[AppConfig]`, and thread the
  single instance through to every consumer (the http4s CORS
  middleware and any WebSocket equivalent). The single-source-of-
  truth dividend is structural in cats-effect-style apps because
  you literally cannot have two copies of the config without
  building them yourself — `Resource[F, AppConfig]` is constructed
  once per process. Spring's `@ConfigurationProperties` plus
  `@EnableConfigurationProperties` plus constructor injection gets
  you to the same place via different mechanics: the singleton
  scope of the bean is what guarantees the "one instance" property
  that pureconfig's effectful loader gives you for free.
- **In Node this would be...** typically a config module that
  reads `process.env` and exports a frozen object, consumed by
  every place that needs an origin (e.g. the `cors` middleware and
  the `socket.io` server config). Same trap: it's easy to read
  `process.env.CORS_ORIGIN` directly in two different files and
  drift them; the discipline is to centralise the read in one
  module and import the typed value everywhere else. The Java
  pattern enforces this structurally; the Node pattern relies on
  team convention.

## Gotchas / things I learned the hard way

- **The EC2 env file may shadow the YAML default.** The deploy
  uses `/opt/chess/.env` to inject env vars into the container. If
  `CHESS_CORS_ALLOWED_ORIGIN_PATTERNS` is set there and still points
  at github.io, this feature ships an artifact whose default is
  correct but whose runtime origin allow-list is wrong. Operator
  follow-up at close time: `cat /opt/chess/.env | grep CHESS_CORS`
  on the EC2 host, and either remove the override (the new default
  suffices) or update its value. Skipping this step means the
  Cloudflare frontend still gets blocked in production even though
  every test is green.
- **Precedent: feature 11.7 was the same shape.** A one-line CORS
  change (`X-Player-Id` to the allowed-headers list) went through
  the full harness cycle: feature in `feature_list.json`, plan in
  `progress/current.md`, IT update, doc update, feature note. This
  feature follows that precedent deliberately — the bar for "what
  warrants a feature" is not the size of the diff, it's whether
  the change is behavioural and user-visible. Both criteria apply
  here.

## To dig deeper

- [Spring Boot externalised configuration](https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.typesafe-configuration-properties)
  — `@ConfigurationProperties` binding semantics and how relaxed
  binding plus env-var defaults compose.
- [Cloudflare Pages — custom domains and preview deployments](https://developers.cloudflare.com/pages/configuration/custom-domains/)
  — the deploy surface that replaced GitHub Pages on the frontend
  side. The `<project>.pages.dev` hostname shape matters when a
  future "allow preview deploys" feature widens the pattern with
  a wildcard like `https://*.chess-frontend-52i.pages.dev`.
- [pureconfig](https://pureconfig.github.io/) — the Scala parallel
  for typed configuration loading; the same single-source-of-truth
  posture that `@ConfigurationProperties` gives us here.

## File map

**Created:**

- `notes/15-cors-cloudflare-origin.md` — this note.

**Modified:**

- `src/main/resources/application.yml` — line 43 default value
  swapped from `https://dariogguillen.github.io` to
  `https://chess-frontend-52i.pages.dev`. Comments above the line
  and the env-var name are unchanged.
- `src/test/java/io/github/dariogguillen/chess/config/CorsConfigIT.java`
  — constant `GITHUB_PAGES_ORIGIN` renamed to
  `CLOUDFLARE_PAGES_ORIGIN` with its value updated; test method
  `preflight_allowedOriginGithubPages_returnsCorsHeaders` renamed
  to `preflight_allowedOriginCloudflarePages_returnsCorsHeaders`;
  class-level JavaDoc bullet updated from "GitHub Pages origin" to
  "Cloudflare Pages origin". The other two tests that referenced
  the constant inherit the new value automatically.
- `docs/architecture.md` — the allowed-origin-patterns bullet now
  reads `https://chess-frontend-52i.pages.dev — production frontend
  on Cloudflare Pages.`
- `README.md` — the "Try it live" Frontend bullet now points at
  `https://chess-frontend-52i.pages.dev/` with the description
  "React/Vite SPA on Cloudflare Pages." The `chess-frontend`
  source-repo link at the end of the end-to-end-flow section
  (`github.com/dariogguillen/chess-frontend`) is unchanged — it
  points at the source repository on GitHub, which stays where it
  is regardless of where the SPA is deployed.

**Cross-repo:** the `chess-frontend` repo migrated to Cloudflare
Pages before this feature was scheduled; this backend change
follows that move. No further cross-repo coordination needed.
