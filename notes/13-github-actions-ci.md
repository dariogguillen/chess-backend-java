# Feature 13 — CI pipeline on GitHub Actions (PR validation)

**Feature ID:** `github-actions-ci` (from `feature_list.json`)

**Status:** in progress

---

## What we built

A pull-request gate. `.github/workflows/ci.yml` runs `./init.sh` on every
PR targeting `main` so regressions are caught **before** they land,
instead of after `deploy.yml` starts pulling them. The three steps that
both workflows share — set up Java 21 Temurin with a Maven cache, then
run `./init.sh` — were extracted into a single composite action at
`.github/actions/build/action.yml`. Both `ci.yml` and `deploy.yml` now
delegate to that composite, so a JDK bump (or any future change to the
verify invocation) is one edit, not two. A CI status badge sits at the
top of the README so the current `main`-vs-PR signal is visible from
the repo landing page.

## Java / Spring concepts that appear

This is a CI feature; the concepts are GitHub-Actions-shaped, not
Spring-annotation-shaped. They are still the ones a Spring Boot
developer has to internalise the day they take a service to production.

- **`pull_request` vs `push` triggers.** GitHub Actions matches workflows
  to events. `on.pull_request.branches: [main]` fires the workflow when
  a PR targeting `main` is opened, reopened, or receives a new commit on
  its head branch. It does **not** fire on `push` to `main` itself —
  that is the deploy workflow's job. The two events are designed to be
  combined: `pull_request` validates the candidate, `push` validates and
  ships the merge. Together they form the gate (PR validation must be
  green to merge) and the deploy (every merge actually deploys). See
  [GitHub's event reference](https://docs.github.com/en/actions/using-workflows/events-that-trigger-workflows#pull_request)
  for the full event payload and the available activity types.
- **Composite actions.** A composite action is a YAML file at
  `<repo>/.github/actions/<name>/action.yml` exposing a sequence of
  steps that another workflow can call as a single `uses:` step. Three
  step kinds are available inside the composite: `uses` (call another
  action), `run` (shell command — `shell` is required, no implicit
  default), and `with`/`inputs` machinery for parameterisation. The
  runner inlines the composite's steps into the calling job at runtime,
  so they share the calling job's workspace, env vars, and runner image.
  This is the idiomatic mechanism for "share a step sequence across
  workflows in the same repo". See the
  [composite-action docs](https://docs.github.com/en/actions/creating-actions/creating-a-composite-action).
- **Reusable workflows (`workflow_call`).** The heavier alternative.
  Instead of inlining steps, the called workflow runs as its **own job**
  on its own runner, with its own checkout, its own cache state, its own
  workspace. Communication happens through `inputs:`, `outputs:`, and
  `secrets:` — a job-level boundary, not a step-level one. The right
  tool when you want to share an *entire job* (e.g., a published-image
  job that takes an `image_tag` input and is called from multiple deploy
  workflows). Wrong tool for "share three prologue steps" — the runner
  boot cost alone (provisioning a second runner, re-doing checkout)
  swamps the few seconds saved by the abstraction. See the
  [reusable-workflow docs](https://docs.github.com/en/actions/sharing-automations/reusing-workflows).
- **`actions/setup-java@v4` with `cache: maven`.** The action provisions
  the requested JDK distribution + version on the runner (here, Temurin
  21) and, when `cache: maven` is set, wraps the run in a
  `actions/cache`-backed Maven local-repository cache. The cache key is
  derived by hashing all `**/pom.xml` files in the repo; a hit restores
  `~/.m2/repository` before the build runs, a miss falls back to a
  full `mvn` download and uploads the populated repo at the end of the
  run for the next job to reuse. Cold-cache add ~60–90s of dependency
  download; warm-cache add ~10s of restore. The hash-on-poms behaviour
  is exactly what you want: a `pom.xml` edit invalidates the cache; a
  source-only edit does not. See the
  [setup-java cache reference](https://github.com/actions/setup-java#caching-packages-dependencies).
- **GitHub Actions `concurrency` for PRs.** `cancel-in-progress: true`
  keyed by `ci-${{ github.event.pull_request.number }}` means: when a
  new commit arrives on a PR while the previous commit's CI run is
  still going, **cancel the in-flight run** and start fresh on the new
  commit. The user only ever sees the latest commit's verdict. This is
  the opposite policy from `deploy.yml`'s `group: deploy-prod,
  cancel-in-progress: false` — deploys queue (because partial deploys
  are bad), PR checks drop (because stale verdicts are noise). The two
  policies coexist deliberately. See the
  [concurrency docs](https://docs.github.com/en/actions/using-jobs/using-concurrency)
  for the full grammar.
- **`permissions: contents: read` as least-privilege baseline.** The
  default `GITHUB_TOKEN` permission set depends on a repo-level setting
  that varies between accounts; declaring `permissions:` explicitly at
  the workflow level pins the token to the minimum surface the workflow
  actually needs. `contents: read` is enough for `actions/checkout@v4`
  to clone the repo; nothing else is needed (no PR comments, no AWS,
  no package writes). The deploy workflow needs `id-token: write` to
  mint the OIDC JWT for AWS; this one does not, and that line is
  deliberately absent. See the
  [token permissions reference](https://docs.github.com/en/actions/security-guides/automatic-token-authentication).

## Decisions taken

**Composite action over duplication.**

- Decision: extract the shared `setup-java + ./init.sh` steps into
  `.github/actions/build/action.yml`, called by both `ci.yml` and
  `deploy.yml` via `uses: ./.github/actions/build`.
- Alternatives: (D) duplicate the three steps inline in `ci.yml`;
  (R) a reusable workflow (`on: workflow_call`) called by both.
- Why: the three-step overlap is exactly the shape composite actions
  exist for. (D) carries real drift risk — feature 11.7 already burned
  us with a CORS allow-list that silently fell out of sync with a
  header added later, and the same shape can recur here (a JDK
  version bump applied to `deploy.yml` but forgotten on `ci.yml`).
  (R) is overkill: a reusable workflow spins up its own runner with
  its own checkout, adding ~30s of overhead and a job-level
  parameterisation surface (`inputs:`/`outputs:`/`secrets:`) we do not
  need for a step-level concern. Composite is the idiomatic middle
  ground for "share a small step sequence in the same repo".

**Composite contains 2 steps, not 3 — checkout stays in the caller.**

- Decision: the composite runs `setup-java` + `./init.sh` only. Each
  calling workflow runs `actions/checkout@v4` as its own first step
  before delegating to the composite.
- Alternatives: include `actions/checkout@v4` as the first step of the
  composite itself, so callers shrink to a single `uses:` step.
- Why: a **local** composite action (`uses: ./.github/actions/build`)
  resolves by path — the runner reads `.github/actions/build/action.yml`
  from the workspace. If the workspace has not been checked out yet,
  the action file does not exist, and the workflow fails before any
  composite step runs. GitHub's composite-action documentation
  confirms this: "If you are using a checkout action, use it before
  the composite action." So checkout cannot live inside the composite
  it is logically a prerequisite for — it has to be the calling
  workflow's first step. The composite still describes its outcome
  from the caller's perspective (run a Maven verify); the checkout
  caveat is documented as a YAML comment at the top of `action.yml`
  and called out again in the `description:` field so the next reader
  is not surprised. See the **Gotchas** section below for the full
  story.

**PR-only validation; deploy.yml stays push-only.**

- Decision: `ci.yml` triggers on `pull_request` only.
- Alternatives: trigger on both `pull_request` and `push: main` so
  every event runs the same gate; or extend `deploy.yml` to also
  validate on PRs.
- Why: `deploy.yml` already validates `main` (its first step is
  `./init.sh`); doubling the validation on the push path would burn a
  full CI run per merge for zero added signal. And widening
  `deploy.yml` to run on PRs would force the OIDC + AWS permissions
  in front of every PR contributor's commit — a security regression
  for no benefit. Keeping the workflows split (PR validation
  permissionless, deploy gated to `main`) matches the principle that
  least-privilege boundaries follow event boundaries.

**`cancel-in-progress: true` on the PR concurrency group.**

- Decision: a new push to a PR cancels the in-flight CI run.
- Alternatives: queue (`cancel-in-progress: false`) so every push gets
  a verdict.
- Why: only the latest commit's verdict matters for a merge decision.
  Burning ~3 minutes of runner time to compute "the commit you just
  force-pushed-over passed" is wasted compute. The opposite policy
  (queue) is correct for `deploy.yml` because partial deploys are
  worse than slow ones — but for PR validation the artifact is the
  verdict itself, not a deploy side effect, and the freshest verdict
  wins.

**Branch protection: documented, not enforced from code.**

- Decision: leave branch protection (block merge on failed CI) to the
  user via the GitHub web UI. Document the requirement in the feature
  note but do not attempt to provision it from the repo.
- Alternatives: configure branch protection via the
  [`gh api`](https://cli.github.com/manual/gh_api) admin endpoint in a
  separate one-shot script, or via Terraform's
  `github_branch_protection` resource.
- Why: the GitHub web UI is the canonical place this lives. Owning it
  from code requires elevated admin tokens (or a separate Terraform
  state for the GitHub provider) and converts a one-click setting into
  a ~50-line PR. The portfolio scope is "workflow validates, badge
  shows the result, user configures merge gating in the UI"; the
  rest is polish for a future feature.

**Badge URL hardcodes the repo path.**

- Decision: the badge URL is the literal
  `https://github.com/dariogguillen/chess-backend-java/actions/workflows/ci.yml/badge.svg`.
- Alternatives: a less coupled relative URL like
  `[![CI](actions/workflows/ci.yml/badge.svg)](actions/workflows/ci.yml)`.
- Why: GitHub's README rendering does not resolve relative URLs for
  external embed sources — the relative form would render as a broken
  image. The full URL is the canonical pattern GitHub itself
  generates from the "Create status badge" button in the Actions UI.
  Hard-coding the owner/repo is acceptable: a repo rename or transfer
  is the rare event where the README updates anyway.

## How this compares to what I know

- **In Scala / sbt.** The closest equivalent is
  [`sbt-github-actions`](https://github.com/sbt/sbt-github-actions),
  which generates `.github/workflows/ci.yml` (and the matching cache
  config) from sbt settings. Run `sbt githubWorkflowGenerate` and the
  plugin spits out a workflow that does the same thing this file does:
  checkout → setup-java → caching → `sbt test`. The advantage is
  drift-free regeneration (change the Scala version in `build.sbt`, the
  next regen updates the workflow); the disadvantage is one more
  plugin to track and an opaque generated file. Java + Maven has no
  equivalent generator — the YAML is handwritten, which is mildly
  painful relative to the Scala side but makes the contract explicit.
- **Composite actions vs SBT cross-build matrices.** sbt-github-actions
  encourages declaring `crossScalaVersions` and generates a matrix
  job from it. The Java equivalent — "validate on JDK 17 and JDK 21"
  — would be a `strategy.matrix.java-version: [17, 21]` block on the
  job, with `actions/setup-java@v4` parameterised by `${{ matrix.java-version }}`.
  We do not need this today (the project pins Java 21), but the
  composite action makes the future migration trivial: add an
  `inputs.java-version` to `action.yml`, thread it into `setup-java`,
  and the calling workflows can fan out into matrices independently.
- **In Node.** GitHub Actions itself is Node-friendly (most marketplace
  actions are Node bundles), but a CI workflow for Node lands at
  basically the same shape: `setup-node@v4` with `cache: npm`, then
  `npm ci && npm test`. The Maven-cache-by-pom-hash pattern is
  identical to `actions/setup-node@v4`'s `package-lock.json` hash key.
  The big cross-stack lesson is that **caching by lockfile hash** is
  one of the few CI primitives that is genuinely portable: the
  semantics ("invalidate when dependencies change, hit otherwise")
  transfer verbatim.
- **Concurrency policies cross-stack.** The
  `cancel-in-progress: true` (drop) vs `false` (queue) decision is the
  same shape as the `kill-on-resubmit` vs `queue-resubmit` knob in any
  CI-as-code system (CircleCI's `cancel-redundant-builds`, GitLab's
  `interruptible:`, Buildkite's `cancel_intermediate_builds`). The
  rule of thumb is independent of stack: drop when the artifact is
  the verdict (validation), queue when the artifact is a side effect
  (deploy).

## Gotchas / things I learned the hard way

- **A local composite action (`uses: ./.github/actions/build`) cannot
  check out the repo it lives in.** I went into this feature assuming
  `actions/checkout@v4` would be the first step of the composite, so
  callers would shrink to a single `uses:` step. But the runner
  resolves a local action by **reading `action.yml` from the
  workspace** — and the workspace is empty until checkout has run.
  Putting checkout inside the composite means the runner cannot find
  the composite to run the checkout. The fix is structural: checkout
  is always the calling workflow's first step, the composite picks up
  on an already-populated workspace, and the caveat is documented at
  the top of `action.yml` (YAML comment) and in the action's
  `description:` field so the next reader catches it before tripping
  on it. This is the kind of misconception that survives a casual
  reading and only surfaces when the workflow actually runs — worth
  documenting as a learning artefact, not hiding.
- **The `pull_request` event uses the merge-commit ref by default.**
  Unlike `push`, which checks out the pushed ref directly, the
  `pull_request` event provides a synthetic `refs/pull/<n>/merge` ref
  that represents "the PR branch merged into the base". `actions/checkout@v4`
  picks this up automatically, so the verify runs against the
  prospective merge result, not just the branch tip. For us this is
  the right default — it catches regressions that only appear after
  a base-into-feature merge. Documenting because the symptom of
  surprise here is "the CI passed on the PR but my local branch
  fails" — the local branch missing a `main` merge is the answer.
- **`shell:` is required on every `run:` step inside a composite
  action.** Top-level workflow `run` steps default to `bash` on
  Linux runners; composite-action `run` steps have **no default**
  and fail to parse without an explicit `shell:`. I set
  `shell: bash` on the `./init.sh` step; the failure mode (rejected
  at workflow parse time before any runner is provisioned) is
  loud, but easy to miss in a first draft. The
  [`run` step reference](https://docs.github.com/en/actions/creating-actions/metadata-syntax-for-github-actions#runsstepsshell)
  is explicit on this.
- **`actionlint` is the tool to validate this YAML offline; it was
  not on PATH in this environment.** Running `which actionlint`
  during verification returned `not found`. The fallback is GitHub's
  own parse-on-push validation — malformed YAML is rejected at the
  Actions API level before a runner starts — but a local
  `actionlint` would catch typos in step ids, mis-cased event names,
  and reference errors *before* the first push. Worth installing
  before the next workflow feature.

## To dig deeper

- [Creating a composite action](https://docs.github.com/en/actions/creating-actions/creating-a-composite-action)
  — GitHub's canonical reference; the "Local action" section covers
  the workspace-checkout caveat that bit me.
- [Events that trigger workflows — `pull_request`](https://docs.github.com/en/actions/using-workflows/events-that-trigger-workflows#pull_request)
  — activity types, the synthetic merge ref, and the
  `pull_request_target` security caveat.
- [Using concurrency](https://docs.github.com/en/actions/using-jobs/using-concurrency)
  — `group:`, `cancel-in-progress:`, and the interaction with
  matrix jobs.
- [`actions/setup-java` — caching dependencies](https://github.com/actions/setup-java#caching-packages-dependencies)
  — exactly what the `cache: maven` knob does under the hood and
  how to customise the cache key if needed.
- [Automatic token authentication & `permissions:`](https://docs.github.com/en/actions/security-guides/automatic-token-authentication)
  — the full permission grammar and which actions need which scopes.
- [`sbt-github-actions`](https://github.com/sbt/sbt-github-actions)
  — for the Scala parallel; the README's "Generated workflows"
  section maps each sbt setting to the resulting YAML.
- [Reusable workflows](https://docs.github.com/en/actions/sharing-automations/reusing-workflows)
  — the heavier alternative we did **not** pick; useful reading to
  internalise the composite-vs-reusable trade-off.

## File map

**Created:**

- `.github/actions/build/action.yml` — composite action with two
  steps (`setup-java` + `./init.sh`). Called by both `ci.yml` and
  `deploy.yml`. YAML comment + `description:` document the
  checkout caveat so the next reader catches it.
- `.github/workflows/ci.yml` — PR validation workflow. Triggers on
  `pull_request` targeting `main`; runs `actions/checkout@v4`
  followed by `uses: ./.github/actions/build`; concurrency keyed
  by PR number with `cancel-in-progress: true`; permissions
  `contents: read` (least-privilege baseline).
- `notes/13-github-actions-ci.md` — this note.

**Modified:**

- `.github/workflows/deploy.yml` — the existing `Checkout` step
  stays as-is; the two prologue steps `Set up Java 21` and
  `Run init.sh (compile + unit + integration tests)` are replaced
  by a single `uses: ./.github/actions/build`. All downstream
  steps (AWS OIDC, ECR login, build/tag/push, EC2 SSH, smoke test)
  remain byte-identical.
- `README.md` — adds the CI status badge between the title and
  the project tagline. The badge URL is the canonical
  GitHub-generated form; clicking it opens the workflow's run
  history.
