# Current session

**Feature:** `backend-cicd-pipeline` (priority 7.7)
**Status:** plan pending approval

---

## Context

Last of three sub-features (7, 7.5, 7.7) that take the backend from
"runs locally with Docker" to "automated deploys on push to main".

- Feature 7 (`backend-containerize`): shipped Dockerfile +
  docker-compose.yml + env-var-with-default pattern.
- Feature 7.5 (`backend-aws-infra`): provisioned AWS Free Tier
  infrastructure with Terraform, deployed manually following the
  runbook. ECR repo exists empty; cloud-init installed Docker +
  Caddy + the `deploy` user.
- **Feature 7.7 (this one)**: GitHub Actions workflow that, on every
  push to `main`:
  1. Runs `./init.sh` (build + tests).
  2. Authenticates to AWS via **OIDC** (no static keys ever).
  3. Builds Docker image, tags with commit SHA + `latest`.
  4. Pushes both tags to ECR.
  5. SSHes into EC2 as `deploy`, pulls the new image from ECR,
     restarts the container via `docker compose pull && up -d`.
  6. Smoke-tests `https://chess-backend.duckdns.org/api/health` from
     the runner.

## Why this feature

Manual deploys (the 10-step procedure from 7.5) are educational but
not sustainable. Every push needs to land in production with zero
manual intervention. This feature is also where OIDC-federated auth
shines: GitHub Actions assumes an AWS IAM role at runtime, with no
long-lived access keys stored anywhere — the modern pattern that
replaces the old `AWS_ACCESS_KEY_ID` + `AWS_SECRET_ACCESS_KEY` secret
storage.

## Approach

### High-level flow

```
push to main
   │
   ▼
GitHub Actions runner (Ubuntu-latest, GitHub-hosted)
   │
   ├─ Stage 1: checkout + setup-java + ./init.sh
   │  └─ if red → fail, no deploy
   │
   ├─ Stage 2: configure-aws-credentials (OIDC) → assume IAM role
   │  └─ short-lived STS credentials, no static keys
   │
   ├─ Stage 3: docker build → docker tag (sha + latest)
   │  └─ docker push to ECR (both tags)
   │
   ├─ Stage 4: SSH to EC2 (deploy user, dedicated CI key)
   │  ├─ aws ecr get-login-password | docker login
   │  ├─ docker compose -f docker-compose.prod.yml pull
   │  ├─ docker compose -f docker-compose.prod.yml up -d
   │  └─ wait for healthcheck (max 60s)
   │
   └─ Stage 5: smoke test from runner
      └─ curl https://chess-backend.duckdns.org/api/health → expect 200
```

### OIDC trust setup (the AWS side)

GitHub Actions provides a JWT token to AWS via the OIDC handshake.
AWS validates it against the GitHub OIDC provider, applies the trust
policy conditions (repo + branch), and issues short-lived STS
credentials. No static keys are stored anywhere.

Components Terraform will provision in this feature:

1. **`aws_iam_openid_connect_provider`** — registers
   `token.actions.githubusercontent.com` as a trusted OIDC provider
   in our AWS account. Thumbprint: `ffffffff...` (well-known public
   value; Terraform docs covers it).
2. **`aws_iam_role` "github_actions"** — assumable by the OIDC
   provider, with a trust policy that restricts:
   - `token.actions.githubusercontent.com:aud = sts.amazonaws.com`
   - `token.actions.githubusercontent.com:sub` matches
     `repo:dariogguillen/chess-backend-java:ref:refs/heads/main`
     (and optionally `:environment:production` if we add envs later).
   The `sub` condition is critical — without it, ANY GitHub repo
   could assume this role.
3. **`aws_iam_role_policy_attachment`** for
   `AmazonEC2ContainerRegistryPowerUser`. The `PowerUser` policy
   allows push + pull + list on all ECR repos in the account. We
   could write a least-privilege custom policy scoped to just our
   repo, but `PowerUser` is the conventional starting point — note
   surfaces this as a polish opportunity.

### Instance profile for EC2 → ECR pull

The EC2 needs to pull from ECR. The cleanest way is via an **IAM
instance profile** attached to the EC2:

4. **`aws_iam_role` "ec2_ecr_pull"** — assumable by `ec2.amazonaws.com`.
5. **`aws_iam_role_policy_attachment`** for
   `AmazonEC2ContainerRegistryReadOnly` (read = pull, list,
   describe — no push from EC2 ever).
6. **`aws_iam_instance_profile`** wrapping the role.
7. Existing `aws_instance.app` gets an `iam_instance_profile`
   attribute pointing at it.

> **Critical**: adding `iam_instance_profile` is an in-place update,
> not a forced replacement. `terraform apply` should NOT destroy
> the running EC2 — the user will see `~` (modify) not `-/+`
> (replace). We will verify this in the `terraform plan` output
> before applying.

### awscli on the EC2

`aws ecr get-login-password` requires the AWS CLI on the EC2. Two
discrepancies to resolve:

- **For future instances**: update `infra/ec2-cloud-init.sh.tpl` to
  `apt install -y awscli` as part of step 1. Future
  `terraform destroy + apply` cycles will produce instances with
  awscli preinstalled.
- **For the currently running EC2** (feature 7.5's instance):
  cloud-init only runs on first boot, and `ignore_changes = [user_data]`
  means a template edit won't re-run it. The user will install
  awscli manually:
  ```bash
  ssh -i ~/.ssh/id_ed25519 ubuntu@18.189.228.186 'sudo apt-get update && sudo apt-get install -y awscli'
  ```
  Documented in `docs/deploy-runbook.md` under a new "One-time
  upgrade for the existing EC2" section.

### Compose file change

`docker-compose.prod.yml` currently has:

```yaml
app:
  image: chess-backend:latest
```

This won't work for `docker compose pull` because there's no
registry. Switch to environment-driven:

```yaml
app:
  image: ${APP_IMAGE:-chess-backend:latest}
```

The deploy step sets `APP_IMAGE` to the ECR URL with the SHA tag:

```bash
APP_IMAGE=546046686081.dkr.ecr.us-east-2.amazonaws.com/chess-backend:<sha>
```

This means:
- **Locally** (no `APP_IMAGE`): defaults to `chess-backend:latest`
  → still works for the manual-deploy fallback.
- **In CI/CD**: the workflow sets the explicit ECR URL + commit SHA
  → immutable, traceable, can be rolled back to any prior commit.

Add `APP_IMAGE` to `/opt/chess/.env` on EC2 (the workflow writes it
via `ssh deploy@... 'echo "APP_IMAGE=..." > /opt/chess/.env'` —
overwriting only the line, see below).

Actually, simpler: include `APP_IMAGE` in the `.env` template that
the workflow generates each deploy. The `.env` already has the
RDS-related vars; the workflow's SSH step appends/replaces
`APP_IMAGE`. To avoid trampling `SPRING_DATASOURCE_PASSWORD` and
friends, the workflow uses `sed -i` to set just that one line.

Alternatively, **simplest**: `.env` stays static (RDS vars), and
the workflow passes `APP_IMAGE` directly as an env var on the
`docker compose` command:

```bash
ssh deploy@... "cd /opt/chess && APP_IMAGE=$ECR_URL:$SHA docker compose -f docker-compose.prod.yml pull && APP_IMAGE=$ECR_URL:$SHA docker compose -f docker-compose.prod.yml up -d"
```

I lean toward this last approach — keeps `.env` immutable across
deploys, the variable lives entirely in the workflow.

### GitHub Actions workflow shape

`.github/workflows/deploy.yml`:

```yaml
name: Deploy to production

on:
  push:
    branches: [main]
  workflow_dispatch:

concurrency:
  group: deploy-prod
  cancel-in-progress: false   # queue, don't drop deploys

permissions:
  id-token: write              # OIDC
  contents: read

jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
          cache: maven

      - name: Run init.sh
        run: ./init.sh

      - name: Configure AWS credentials (OIDC)
        uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: ${{ secrets.AWS_DEPLOY_ROLE_ARN }}
          aws-region: us-east-2

      - name: Login to ECR
        id: ecr-login
        uses: aws-actions/amazon-ecr-login@v2

      - name: Build, tag, push
        env:
          ECR_REPO: ${{ steps.ecr-login.outputs.registry }}/chess-backend
          IMAGE_TAG: ${{ github.sha }}
        run: |
          docker build -t chess-backend:local .
          docker tag chess-backend:local $ECR_REPO:$IMAGE_TAG
          docker tag chess-backend:local $ECR_REPO:latest
          docker push $ECR_REPO:$IMAGE_TAG
          docker push $ECR_REPO:latest

      - name: Deploy to EC2
        env:
          ECR_REPO: ${{ steps.ecr-login.outputs.registry }}/chess-backend
          IMAGE_TAG: ${{ github.sha }}
          EC2_HOST: chess-backend.duckdns.org
          DEPLOY_SSH_KEY: ${{ secrets.DEPLOY_SSH_KEY }}
        run: |
          mkdir -p ~/.ssh
          echo "$DEPLOY_SSH_KEY" > ~/.ssh/deploy_key
          chmod 600 ~/.ssh/deploy_key
          ssh-keyscan -H $EC2_HOST >> ~/.ssh/known_hosts

          ssh -i ~/.ssh/deploy_key deploy@$EC2_HOST \
            "aws ecr get-login-password --region us-east-2 | docker login --username AWS --password-stdin $ECR_REPO && \
             cd /opt/chess && \
             APP_IMAGE=$ECR_REPO:$IMAGE_TAG docker compose -f docker-compose.prod.yml pull && \
             APP_IMAGE=$ECR_REPO:$IMAGE_TAG docker compose -f docker-compose.prod.yml up -d"

      - name: Smoke test
        run: |
          for i in {1..12}; do
            if curl -fsS https://chess-backend.duckdns.org/api/health | grep -q '"status":"UP"'; then
              echo "Health check passed (attempt $i)"
              exit 0
            fi
            echo "Health check attempt $i failed, retrying in 5s..."
            sleep 5
          done
          echo "Health check failed after 60s"
          exit 1
```

Key design notes:

- **`permissions: id-token: write`** is what enables OIDC. Without
  this, `configure-aws-credentials` can't fetch the JWT.
- **`concurrency: cancel-in-progress: false`** queues deploys
  instead of cancelling. Two rapid pushes both deploy in order.
  Alternative `true` would discard the older deploy mid-flight,
  which is riskier (image partially pushed but compose not
  restarted).
- **Smoke test loop** retries up to 60s, in case the container
  hasn't finished restarting when the workflow first probes.
- **`ssh-keyscan`** writes the EC2 host key to `known_hosts` to
  avoid the interactive "fingerprint accept" prompt. Less paranoid
  than pinning the host key explicitly, but acceptable.
- **`.dockerignore` already in place** from feature 7 — Maven repo
  and target/ are excluded, build is fast.

### SSH key for GitHub Actions

`deploy` user's `authorized_keys` on the EC2 currently contains
only the user's personal `~/.ssh/id_ed25519.pub`. The workflow
needs a **dedicated CI key** so GHA's SSH access is separable from
the user's personal access (rotation, audit, blast radius).

User-side setup (one-time, documented in the runbook):

```bash
ssh-keygen -t ed25519 -f ~/.ssh/chess_ci -N "" -C "github-actions-chess-backend"
# Append public key to deploy user's authorized_keys:
ssh -i ~/.ssh/id_ed25519 deploy@18.189.228.186 \
  "cat >> ~/.ssh/authorized_keys" < ~/.ssh/chess_ci.pub
# Add private key as GitHub Actions secret:
gh secret set DEPLOY_SSH_KEY < ~/.ssh/chess_ci
# Add the IAM role ARN (from terraform output after this feature's apply):
gh secret set AWS_DEPLOY_ROLE_ARN -b "<arn from terraform>"
```

`gh secret set` requires `gh auth login` first — the user might
not have `gh` configured yet. Runbook documents both paths
(GitHub web UI or `gh` CLI).

## Terraform module structure

```
infra/
├── main.tf                          (modified — add OIDC + IAM resources, EC2 instance profile attribute)
├── variables.tf                     (modified — add github_repo variable, default "dariogguillen/chess-backend-java")
├── outputs.tf                       (modified — add github_actions_role_arn output)
├── ec2-cloud-init.sh.tpl            (modified — add awscli to apt install list)
```

No new files in `infra/` — additions go into existing files. The
`.terraform.lock.hcl` may auto-update if the AWS provider needs a
newer minor version.

## Files to create

```
.github/workflows/deploy.yml                    (new; the production deploy workflow)
notes/07.7-backend-cicd-pipeline.md             (new; feature note)
```

## Files to modify

- `infra/main.tf`: add IAM OIDC provider, IAM role for GHA, IAM role
  for EC2 instance profile, ECR readonly policy attachment, ECR
  power-user policy attachment, instance profile, `iam_instance_profile`
  attribute on `aws_instance.app`.
- `infra/variables.tf`: `github_repo` variable
  (default `"dariogguillen/chess-backend-java"`),
  `github_branch` variable (default `"main"`).
- `infra/outputs.tf`: `github_actions_role_arn` output.
- `infra/ec2-cloud-init.sh.tpl`: add `awscli` to the apt install line.
- `docker-compose.prod.yml`: change `image: chess-backend:latest` to
  `image: ${APP_IMAGE:-chess-backend:latest}`.
- `docs/deploy-runbook.md`: add three new sections:
  1. "One-time upgrade for the existing EC2" — installs awscli on
     the running instance via SSH.
  2. "Add the CI SSH key to deploy user" — generates the dedicated
     key, appends pubkey to authorized_keys, sets GitHub secret.
  3. "Trigger and monitor a deploy" — pushes a commit, watches the
     Actions tab, what to look for in each stage.
  Also add a high-level diagram of the CI/CD flow.
- `README.md`: extend the existing "Deployment" section with a
  one-paragraph mention of the CI/CD automation, with a link to
  the workflow file.

## Files unchanged

- `Dockerfile`, application code, tests — no Java changes.
- `pom.xml` — no new dependencies.
- `docker-compose.yml` (local dev) — stays as-is.
- `application.yml` — already has `forward-headers-strategy: framework`
  from the 7.5 amendment. The next deploy via this workflow will
  push that fix to production for the first time.

## Verification

**Automated:**

- `./init.sh` exits 0 unchanged.
- `terraform fmt -check infra/` and `terraform validate` clean.
- `terraform plan` produces:
  - `~` (modify in-place) on `aws_instance.app` — adding the
    `iam_instance_profile` attribute. Verify the diff is ONLY that
    attribute change, NOT a full replacement (`-/+`). If Terraform
    insists on `-/+`, stop and surface to the user before applying.
  - `+` (create) for the OIDC provider, the two IAM roles, the
    policy attachments, the instance profile. ~7-8 new resources.
- The workflow YAML is well-formed: `actionlint` or a manual
  `yq eval` parse confirms no syntax errors.

**Manual (user-executed, documented in the runbook updates):**

1. `terraform apply` — applies the IAM + OIDC changes. EC2 stays
   running (in-place update).
2. `ssh ubuntu@<eip> 'sudo apt install -y awscli'` — one-shot install.
3. `ssh-keygen ...` — create the dedicated CI key, append pubkey
   to `deploy@<eip>:.ssh/authorized_keys`.
4. `gh secret set DEPLOY_SSH_KEY` and
   `gh secret set AWS_DEPLOY_ROLE_ARN` — or the same via GitHub
   web UI.
5. Push a small commit to main (e.g., a trivial README touch).
6. Watch the workflow run in the Actions tab. Confirm:
   - `./init.sh` step green.
   - AWS credentials configured (OIDC success).
   - ECR login + push success (the ECR repo in console shows the
     new image with the commit SHA tag).
   - SSH + docker compose pull + up -d succeeds.
   - Smoke test step returns 200 on `/api/health`.
7. Confirm in browser: `https://chess-backend.duckdns.org/v3/api-docs | jq '.servers'`
   now shows `https://...` (the `forward-headers-strategy` fix is
   live for the first time).

## Java / Spring concepts to add to `notes/07.7-backend-cicd-pipeline.md`

This is a CI/CD feature; concepts are DevOps + security, not
Java/Spring per se.

- **OIDC-federated authentication**: the modern replacement for
  long-lived static API keys. Walk through the JWT flow: GHA
  generates a token, AWS validates against GitHub's public keys
  (via the OIDC provider thumbprint), checks the trust policy
  conditions (repo + branch), issues short-lived STS credentials.
  Why this is safer than static keys (no leakable secret, scoped
  to specific workflows, automatic expiry).
- **The `sub` claim restriction**: how
  `repo:owner/repo:ref:refs/heads/main` scopes the role to ONE
  branch in ONE repo. Without this, any GitHub Actions workflow
  could assume the role.
- **IAM instance profile vs IAM user keys**: how the EC2 picks up
  credentials silently via the EC2 metadata service (IMDSv2) when
  awscli runs on it. No `aws configure` needed.
- **Docker image tag strategies**: `latest` (mutable, convenient)
  vs commit SHA (immutable, rollback-friendly). Pushing both lets
  you `docker pull <repo>:latest` for casual use AND
  `docker pull <repo>:<sha>` for traceability.
- **`docker compose pull` + `up -d`**: how compose v2 handles a
  new image. It pulls only changed layers (lazy diff), then
  recreates containers whose image hash changed; healthy
  containers stay untouched (redis here).
- **GitHub Actions concurrency**: `cancel-in-progress: false` vs
  `true`. Queue-vs-drop trade-off in deploy pipelines.
- **`permissions: id-token: write`**: how this single line is what
  enables OIDC. Misconfiguration = mysterious AWS auth failures.

## Decisions to surface in the note

- **OIDC over static IAM user keys**: cv-signal premium, security
  best practice. Static keys would also work but rotate-by-hand
  forever.
- **`AmazonEC2ContainerRegistryPowerUser` on GHA role**: convenient
  starting point. A custom least-privilege policy scoped to
  `arn:aws:ecr:us-east-2:<account>:repository/chess-backend` would
  be stricter. Out of scope for portfolio; surface as polish.
- **`AmazonEC2ContainerRegistryReadOnly` on EC2 instance profile**:
  strictly minimum. EC2 only pulls.
- **Dedicated CI SSH key, not reuse personal**: separation of
  concerns + rotation + audit. Worth the 60-second setup.
- **`docker compose pull && up -d` over docker save | ssh | docker load**:
  faster, registry-native, leverages the ECR repo we already
  provisioned. The manual flow stays in the runbook as a fallback.
- **In-place IAM instance profile attach (not replace)**: Terraform
  attribute-level diff. Verify before applying.
- **awscli on EC2**: technically the credential helper plugin
  (`amazon-ecr-credential-helper`) is more minimal. awscli is more
  flexible and universally known.
- **Concurrency `cancel-in-progress: false`**: rapid main pushes
  queue instead of dropping. For a portfolio with sparse pushes,
  trivial.
- **No staging environment**: prod-only deploys. Trade-off: faster
  iteration, no env drift. Out of scope, mention in note.

## Public API and architecture impact

- **Public API change (README)?** **Yes** — extend "Deployment"
  section with the CI/CD paragraph + link to workflow file.
- **Architectural decision (`docs/architecture.md`)?** **Yes** —
  add a "Deploy automation" subsection (sibling to "Deployment
  artifact") describing the OIDC + ECR + GHA flow at a paragraph
  level.
- **Cross-repo coordination?** **No** — purely internal to this
  repo. Frontend is unaffected.

## Out of scope

- Staging environment / multi-env (no `staging` branch, no
  separate AWS account). Production is the only target.
- Blue-green or canary deploys. Plain rolling restart of the app
  container (docker compose handles it).
- Slack/email/Discord notifications on deploy success/failure.
  GitHub Actions UI is the only notification path.
- PR validation workflow (separate file, only runs `./init.sh` on
  pull requests). This was originally scoped as feature 12
  (`github-actions-ci`); we'll re-scope feature 12 to that role
  after 7.7 closes.
- Database migration coordination (pre-deploy schema check,
  rollback plan). Flyway runs automatically on Spring Boot startup;
  for now this is acceptable.
- Image signing (cosign), SBOM generation, SAST/DAST scans.
- ECR image lifecycle beyond "keep last 5" (which we already have).
- Least-privilege custom IAM policy for the GHA role (using
  `PowerUser` for now).
- Custom OIDC provider thumbprint rotation (Terraform's default
  GitHub thumbprint is good for years).
- Branch protection rules on `main` (out of scope; user's call
  whether to add via GitHub UI).
- AWS Systems Manager Session Manager as an SSH alternative
  (cleaner, no SSH key handling, but adds an IAM dimension —
  surface as future polish).

## Definition of done

- `.github/workflows/deploy.yml` exists and is well-formed.
- AWS OIDC trust configured: identity provider + IAM role +
  trust policy scoped to `repo:dariogguillen/chess-backend-java:ref:refs/heads/main`.
- IAM instance profile attached to the EC2; awscli installed on
  the running EC2 (manual one-shot) + on future instances
  (cloud-init template updated).
- A push to main triggers the workflow; the workflow:
  - Runs `./init.sh` green.
  - Authenticates to AWS via OIDC (no static keys).
  - Pushes `chess-backend:<sha>` and `chess-backend:latest` to ECR
    (visible in the ECR console).
  - SSHes into EC2, pulls + restarts the container.
  - Smoke-tests `/api/health` and reports green.
- `https://chess-backend.duckdns.org/v3/api-docs | jq '.servers'`
  now returns `https://...` (the 7.5 fix is live).
- `docker-compose.prod.yml` reads `APP_IMAGE` env var; defaults to
  `chess-backend:latest` if unset.
- `docs/deploy-runbook.md` extended with three new sections
  (one-time EC2 upgrade, CI SSH key setup, trigger/monitor a deploy)
  + CI/CD flow diagram.
- `README.md`'s "Deployment" section mentions the automation +
  links to the workflow.
- `notes/07.7-backend-cicd-pipeline.md` exists, follows the
  template, covers the concepts and decisions listed above.
- `./init.sh` passes unchanged.

After reviewer approval, the leader reports to the user and **waits
for the user's explicit OK** before flipping `feature_list.json` to
`done` and rolling to the next pending feature (which will be
8 `redis-active-state`).
