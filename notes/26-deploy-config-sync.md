# Feature 26 — Sync docker-compose.prod.yml to EC2 on every deploy

**Feature ID:** `deploy-config-sync` (from `feature_list.json`)

**Status:** in progress

---

## What we built

The deploy workflow now copies the repo's `docker-compose.prod.yml` to
`/opt/chess/docker-compose.prod.yml` on the EC2 host before it runs
`docker compose pull && up -d`. This eliminates config drift: until now
the host's copy of the compose file was independent of the repo's, so
editing the repo yml never reached production. The `.env` on the host —
which holds operator-managed RDS credentials — is deliberately left
untouched.

This is an operational/infra change only. No application code, no
`pom.xml`, no tests changed.

## Java / Spring concepts that appear

None — this feature touches only the CI/CD pipeline
(`.github/workflows/deploy.yml`) and docs. The relevant mechanics are
GitHub Actions and SSH, not the JVM:

- **GitHub Actions step env + `run` shell** — the change lives inside an
  existing step's `run:` block (a Bash script with `set -euo pipefail`).
  The step already materializes `~/.ssh/deploy_key` from the
  `DEPLOY_SSH_KEY` secret and populates `~/.ssh/known_hosts` via
  `ssh-keyscan`. We reused that same key for an `scp`, which guarantees
  ordering: putting the `scp` line above the `ssh ... up -d` line is
  enough to guarantee the new yml is in place before compose reads it.
- **`scp` as the `deploy` user** — copying as `deploy` means the file
  lands with `deploy:deploy` ownership automatically; no post-copy
  `chown`/`chmod` is needed. The compose file carries no secrets (those
  live in `.env`), so file mode is not security-sensitive here.
- **Idempotency** — re-copying an unchanged file is a redundant write,
  not a behavior change. `docker compose up -d` only recreates a
  container when the effective config or image actually changes, so a
  no-op yml sync followed by a same-image pull restarts nothing.

## Decisions taken

- **Decision:** add the `scp` inside the existing `Deploy to EC2 over
  SSH` step, reusing `~/.ssh/deploy_key`, rather than a separate step or
  an action like `appleboy/scp-action`.
  - **Alternatives considered:** (1) a dedicated `scp` step with its own
    key setup; (2) a third-party `scp`/`rsync` GitHub Action;
    (3) `rsync --checksum` for a "only-if-changed" copy.
  - **Why this one:** the existing step already loads the key and the
    `known_hosts` entry, so an inline `scp` adds two lines and zero new
    setup. A separate step would duplicate the key/`known_hosts`
    plumbing. A third-party action adds a supply-chain dependency for
    something one `scp` line does. `rsync` would need rsync present on
    both ends and buys nothing here: the file is tiny and an unconditional
    overwrite is already idempotent for our purposes.

- **Decision:** sync the yml but never the `.env`.
  - **Alternatives considered:** also templating/syncing `.env` from CI
    secrets.
  - **Why this one:** the `.env` holds RDS credentials that must not
    enter the repo or the CI logs. It is operator-managed on the host
    (runbook section 7) and stays that way. Keeping it out of the
    automation is the whole point — only non-secret config (the compose
    topology) is version-controlled and synced.

- **Decision:** put the `scp` strictly *before* the `ssh ... pull && up
  -d`.
  - **Why:** correctness. The acceptance requires the new yml to be the
    one compose reads. Sequential lines in one Bash `run` block with
    `set -e` give that ordering for free.

## How this compares to what I know

- **In Scala / Typelevel this would be...** there is no direct language
  analog — this is deploy plumbing, not application code. The closest
  mental model is the difference between *baking config into the
  artifact* vs *mounting it at the target*. Before this change, the
  production compose file was like a config file that had been hand-
  edited on the server and was no longer reproducible from source — the
  same smell as an `application.conf` that drifted from what's in git.
  The fix makes the deploy *declarative from the repo*: the source tree
  is the source of truth, the same instinct behind keeping all of
  `application.conf` / `reference.conf` in version control and never
  hot-patching it on a box.
- **In Node this would be...** the same drift problem you get when a PM2
  ecosystem file or a `docker-compose.yml` is edited live on the server
  instead of being shipped from the repo. The fix is the equivalent of a
  deploy script that `rsync`s/`scp`s the repo's compose/config up before
  restarting — pushing the repo's copy so the box can't drift.

The key parallel both ways: **secrets stay out of the synced artifact**
(here, `.env`), exactly as you'd keep credentials in a secrets manager /
host-managed env file rather than committing them.

## Gotchas / things I learned the hard way

- The smoke test in the acceptance ("edit the yml, push, confirm the
  file on EC2 changed") **cannot be run from here** — it requires a real
  deploy (a push to `main` or a `workflow_dispatch`), and commits/pushes
  are the user's job. That verification is pending the next deploy.
- `./init.sh` does not exercise the workflow at all (it is compile + lint
  + test of the app), so a green `init.sh` proves the app is unbroken but
  proves nothing about the deploy change. Workflow correctness here rests
  on YAML-syntax validation and careful reading of the step ordering.

## To dig deeper

- GitHub Actions — running shell commands in steps:
  <https://docs.github.com/en/actions/using-workflows/workflow-syntax-for-github-actions#jobsjob_idstepsrun>
- `scp(1)` man page (the `-i identity_file` flag):
  <https://man.openbsd.org/scp>
- Docker Compose `up` recreation semantics (why an unchanged config is a
  no-op): <https://docs.docker.com/reference/cli/docker/compose/up/>

## File map

Where this feature lives in the repo.

- `.github/workflows/deploy.yml` — added an `scp` of
  `docker-compose.prod.yml` to `/opt/chess/` inside the `Deploy to EC2
  over SSH` step, before `docker compose pull && up -d`; updated the
  header comment describing stage 4.
- `docs/architecture.md` — `Deploy automation` section now documents the
  repo→EC2 yml sync and the `.env`'s continued operator-managed status.
- `docs/deploy-runbook.md` — CI/CD flow diagram, a new "Config sync"
  subsection, and the section-13 deploy bullet all describe the sync and
  the untouched `.env`.
- `notes/26-deploy-config-sync.md` — this note.
