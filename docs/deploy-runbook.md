# Deployment runbook

This runbook is the canonical procedure for deploying `chess-backend-java`
to AWS Free Tier. The first-time manual procedure (sections 1–10) was
delivered by feature 7.5; feature 7.7 layers automated deploys on top
(sections 11–13).

The live URL is `https://chess-backend.duckdns.org`.

---

## Architecture: CI/CD flow (feature 7.7)

```
push to main / workflow_dispatch
        │
        ▼
┌─────────────────────────────────────────────────────────────────┐
│ GitHub Actions runner (ubuntu-latest)                           │
│   actions/checkout@v4                                           │
│   actions/setup-java@v4   (temurin 21, cache: maven)            │
│   ./init.sh               (compile + unit + IT)                 │
│                                                                 │
│   aws-actions/configure-aws-credentials@v4 ── OIDC JWT ──┐      │
│                                                          │      │
│   aws-actions/amazon-ecr-login@v2                        │      │
│                                                          ▼      │
│   docker build → tag (<sha>, latest) → docker push  ─►  AWS STS │
│                                                                 │
│   ssh deploy@chess-backend.duckdns.org                          │
│     aws ecr get-login-password | docker login                   │
│     export APP_IMAGE_REPO=<ecr-url> APP_IMAGE_TAG=<sha>          │
│     docker compose -f docker-compose.prod.yml pull && up -d     │
│                                                                 │
│   curl https://chess-backend.duckdns.org/api/health  ──► "UP"   │
└─────────────────────────────────────────────────────────────────┘
        │                                  ▲
        │ push image                       │ pull image
        ▼                                  │
┌──────────────────┐               ┌────────────────────┐
│ ECR (chess-      │               │ EC2 (deploy user,  │
│ backend repo)    │ ─────────────►│ docker compose,    │
│                  │               │ awscli + IMDSv2)   │
└──────────────────┘               └────────────────────┘
                                            │
                                            ▼
                                   ┌────────────────────┐
                                   │ Caddy → :443 TLS   │
                                   │ → app :8080        │
                                   └────────────────────┘
```

Two IAM identities make the flow keyless:

- `chess-backend-github-actions` — assumable only from
  `repo:dariogguillen/chess-backend-java:ref:refs/heads/main`. Pushes
  images to ECR.
- `chess-backend-ec2-ecr-pull` — attached to the EC2 via an instance
  profile. Read-only on ECR. EC2 reads credentials from IMDSv2; no
  `aws configure` ever runs on the host.

---

## Prerequisites

Confirm each item before running anything:

- [ ] **AWS account** with billing alerts enabled. The Terraform stack
  in this repo provisions a $1/month Budget alarm on top, but the
  account-level alerting is yours to keep on.
- [ ] **AWS CLI installed and configured.** Verify with:
  ```bash
  aws sts get-caller-identity
  ```
  The response must show your account ID and a usable identity. The
  Terraform AWS provider reads credentials from the same chain (env
  vars, shared config, SSO), so a working CLI implies a working
  Terraform run.
- [ ] **Terraform >= 1.6** on `PATH`. Check with `terraform version`.
- [ ] **An SSH key pair** for the EC2 admin user, e.g.
  `~/.ssh/id_ed25519` + `~/.ssh/id_ed25519.pub`. Generate one if you
  do not have it with `ssh-keygen -t ed25519`.
- [ ] **Duck DNS account + reserved subdomain**. Sign in at
  <https://www.duckdns.org>, reserve `chess-backend.duckdns.org` (or
  whatever you chose for `duckdns_subdomain`). Note the token; you
  will paste the EC2 Elastic IP into the Duck DNS UI after
  `terraform apply`.
- [ ] **Docker installed locally** for the image build step
  (`docker --version`, must support `docker compose`).

---

## 1. Fill `infra/terraform.tfvars`

From the repo root:

```bash
cp infra/terraform.tfvars.example infra/terraform.tfvars
```

Open `infra/terraform.tfvars` and replace every placeholder. Generate
the database password with:

```bash
openssl rand -base64 24
```

Paste the output into `db_password` (it is base64, so no quoting
issues). Set `alarm_email` to a real address you check — AWS will
send a subscription confirmation email that you must accept before
the budget alarm becomes active. Adjust `ssh_public_key_path` and
`deploy_ssh_public_key_path` to your actual key path(s); for the
first deploy it is fine to use the same key for both.

`infra/terraform.tfvars` is in `.gitignore`. Do not commit it.

---

## 2. `terraform apply`

From `infra/`:

```bash
cd infra
terraform init
terraform validate
terraform fmt -check
terraform plan -out=tfplan
terraform apply tfplan
```

The apply takes roughly 8 minutes — RDS instance creation is the slow
step. When it finishes, save the outputs:

```bash
terraform output
```

The values you will use:

- `ec2_elastic_ip` — paste this into Duck DNS in the next step.
- `rds_address` — the Postgres hostname for your `.env` file.
- `ssh_command` — paste-ready SSH command for the `ubuntu` admin user.
- `duckdns_fqdn` — the public hostname you will `curl` at the end.

---

## 3. Point Duck DNS at the Elastic IP

Sign in at <https://www.duckdns.org>. Find your `chess-backend`
subdomain, paste the `ec2_elastic_ip` value into the "current ip"
field, and click "update ip". Within a minute or two, `dig +short
chess-backend.duckdns.org` (or `nslookup`) should return your EIP.

DNS propagation is the prerequisite for Let's Encrypt's HTTP-01
challenge in step 8; without it Caddy will fail to obtain the
certificate and the HTTPS endpoint will not come up.

---

## 4. Wait for cloud-init

SSH to the instance:

```bash
ssh -i ~/.ssh/id_ed25519 ubuntu@<ec2_elastic_ip>
```

Once in, check cloud-init status:

```bash
cloud-init status --wait
```

`status: done` confirms Docker, Caddy, the `deploy` user, and the
Caddyfile are all in place. Total time from `terraform apply`: ~3–5
minutes. Confirm the two installs:

```bash
docker --version
caddy version
```

If `cloud-init status` reports `error`, inspect the log:

```bash
sudo tail -n 200 /var/log/cloud-init-output.log
```

---

## 5. Build the image locally

From the repo root on your laptop:

```bash
docker compose build
docker tag chess-backend-java-app chess-backend:latest
```

`docker compose build` produces an image named after the compose
project (`chess-backend-java-app` by default). The retag step gives
it the name the production compose file expects (`chess-backend:latest`).

---

## 6. Transfer the image to EC2

The Docker socket on EC2 belongs to `root` / `docker` group. The
`ubuntu` user is in `sudo` and the `deploy` user is in `docker`. The
simplest path is `sudo docker load` over SSH:

```bash
docker save chess-backend:latest | gzip \
  | ssh -i ~/.ssh/id_ed25519 ubuntu@<ec2_elastic_ip> \
    'gunzip | sudo docker load'
```

The image compresses to roughly 90 MB with `gzip` (a little larger than
`bzip2` would produce, but `gzip` is preinstalled on every Ubuntu cloud
AMI while `bzip2` is not — choosing the universally-present tool saves a
cloud-init step). On a typical home connection this takes about a
minute. Feature 7.7 replaces this hand-off with an ECR pull triggered
from CI; for the first deploy the `docker save | ssh | docker load`
pattern keeps the loop tight.

---

## 7. Configure `/opt/chess/.env` on EC2

Create a local `prod.env` file (do not commit it):

```bash
cat > /tmp/prod.env <<EOF
SPRING_DATASOURCE_URL=jdbc:postgresql://<rds_address>:5432/chess
SPRING_DATASOURCE_USERNAME=chess
SPRING_DATASOURCE_PASSWORD=<the_value_you_pasted_into_db_password>
EOF
```

Transfer it and lock down the permissions:

```bash
scp -i ~/.ssh/id_ed25519 /tmp/prod.env ubuntu@<ec2_elastic_ip>:/tmp/prod.env
ssh -i ~/.ssh/id_ed25519 ubuntu@<ec2_elastic_ip> \
  'sudo install -m 600 -o deploy -g deploy /tmp/prod.env /opt/chess/.env && rm /tmp/prod.env'
rm /tmp/prod.env
```

Verify:

```bash
ssh -i ~/.ssh/id_ed25519 ubuntu@<ec2_elastic_ip> \
  'sudo -u deploy cat /opt/chess/.env'
```

---

## 8. Transfer `docker-compose.prod.yml`

From the repo root:

```bash
scp -i ~/.ssh/id_ed25519 docker-compose.prod.yml \
  ubuntu@<ec2_elastic_ip>:/tmp/docker-compose.prod.yml
ssh -i ~/.ssh/id_ed25519 ubuntu@<ec2_elastic_ip> \
  'sudo install -m 644 -o deploy -g deploy /tmp/docker-compose.prod.yml /opt/chess/docker-compose.prod.yml && rm /tmp/docker-compose.prod.yml'
```

---

## 9. Start the stack

```bash
ssh -i ~/.ssh/id_ed25519 ubuntu@<ec2_elastic_ip>
sudo -u deploy bash -c 'cd /opt/chess && docker compose -f docker-compose.prod.yml up -d'
```

Wait ~60 seconds for Spring Boot to warm up. Check container health:

```bash
sudo -u deploy docker compose -f /opt/chess/docker-compose.prod.yml ps
sudo -u deploy docker compose -f /opt/chess/docker-compose.prod.yml logs --tail=50 app
```

When the app starts requesting `/api/health`, Caddy will issue an
HTTP-01 challenge against the Duck DNS hostname and obtain the TLS
certificate from Let's Encrypt automatically. Watch Caddy's progress:

```bash
sudo journalctl -u caddy -f
```

---

## 10. Smoke test

From your laptop:

```bash
curl -sS https://chess-backend.duckdns.org/api/health | jq .
```

Expected: a 200 with a JSON body containing `status`, `version`, and
`timestamp` fields. Then open the OpenAPI explorer in a browser:

<https://chess-backend.duckdns.org/swagger-ui.html>

The Swagger UI should render with every documented endpoint visible
(health, rooms, games).

If both succeed, the deploy is complete.

---

## 11. One-time upgrade for the existing EC2 (feature 7.7)

Feature 7.7's CI workflow has the EC2 invoke `aws ecr get-login-password`
to log into ECR. That requires the AWS CLI on the host. Feature 7.7
updates `infra/ec2-cloud-init.sh.tpl` to install the AWS CLI on first
boot, but **cloud-init only runs once**: instances created before 7.7
(i.e. the running production EC2) do not pick up the change. The
Terraform resource also has `lifecycle { ignore_changes = [user_data] }`,
so a template edit will not re-render — and re-running cloud-init would
still be the wrong tool because subsequent steps (Docker install, the
`deploy` user, the Caddyfile) are already in place.

The fix is a one-time manual install on the existing instance. Ubuntu
24.04 LTS removed the `awscli` package from its default apt repositories
(AWS no longer ships CLI v1 via apt, and v2 is distributed only via the
official installer), so the canonical path on this AMI is the v2 zip:

```bash
ssh -i ~/.ssh/id_ed25519 ubuntu@<ec2_elastic_ip> '
  sudo apt-get update && \
  sudo apt-get install -y unzip && \
  curl -fsSL "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o /tmp/awscliv2.zip && \
  unzip -q /tmp/awscliv2.zip -d /tmp && \
  sudo /tmp/aws/install && \
  rm -rf /tmp/awscliv2.zip /tmp/aws
'
```

Verify:

```bash
ssh -i ~/.ssh/id_ed25519 ubuntu@<ec2_elastic_ip> 'which aws && aws --version'
```

Expect a path of `/usr/local/bin/aws` and a version line like
`aws-cli/2.x.x Python/...`. Future EC2 instances created by
`terraform destroy + apply` will have AWS CLI v2 preinstalled by
cloud-init and skip this step.

---

## 12. Add the CI SSH key to the `deploy` user (feature 7.7)

The CI workflow SSHes to the EC2 as `deploy`. Reusing your personal SSH
key for that would conflate "your developer access" with "automated CI
access" — separate keys mean you can rotate one without breaking the
other and audit logs cleanly tell the two apart.

Generate a dedicated ed25519 keypair locally (no passphrase: CI cannot
type one):

```bash
ssh-keygen -t ed25519 -f ~/.ssh/chess_ci -N "" -C "github-actions-chess-backend"
```

Append the public half to the `deploy` user's `authorized_keys` on the
EC2 (read it back to confirm both entries are present):

```bash
ssh deploy@<ec2_elastic_ip> 'cat >> ~/.ssh/authorized_keys' < ~/.ssh/chess_ci.pub
ssh deploy@<ec2_elastic_ip> 'wc -l ~/.ssh/authorized_keys && cat ~/.ssh/authorized_keys'
```

Smoke-test the new key end-to-end:

```bash
ssh -i ~/.ssh/chess_ci deploy@<ec2_elastic_ip> 'whoami && docker --version'
```

Expect `deploy` plus a Docker version line.

### Register the GitHub Actions secrets

Two secrets are required:

1. `AWS_DEPLOY_ROLE_ARN` — output by Terraform after feature 7.7's
   apply (`terraform output -raw github_actions_role_arn`).
2. `DEPLOY_SSH_KEY` — the **private** half of the keypair you just
   generated (`~/.ssh/chess_ci`).

Two paths to set them. **`gh` CLI** is the fastest:

```bash
# One-time: authenticate against GitHub.
gh auth login

# From the repo root:
terraform -chdir=infra output -raw github_actions_role_arn \
  | gh secret set AWS_DEPLOY_ROLE_ARN
gh secret set DEPLOY_SSH_KEY < ~/.ssh/chess_ci

# Verify both are registered (values are write-only; gh only lists names).
gh secret list
```

**GitHub web UI** (equivalent, if you have not configured `gh`):

1. Open the repository on github.com.
2. Settings → Secrets and variables → Actions → "New repository secret".
3. Add `AWS_DEPLOY_ROLE_ARN` — paste the ARN exactly as printed by
   `terraform output`.
4. Add `DEPLOY_SSH_KEY` — paste the **entire** content of
   `~/.ssh/chess_ci`, including the `-----BEGIN OPENSSH PRIVATE KEY-----`
   and `-----END OPENSSH PRIVATE KEY-----` banner lines and the trailing
   newline. Missing the trailing newline is a common cause of opaque
   "invalid format" failures at runtime.

---

## 13. Trigger and monitor a deploy (feature 7.7)

With sections 11 and 12 complete, the workflow is ready to run. The
cleanest trigger is a trivial commit on `main`:

```bash
echo "" >> README.md   # one-line whitespace touch
git add README.md
git commit -m "chore: trigger CI deploy"
git push origin main
```

(Alternatively: open the Actions tab on github.com and run
**Deploy to production** via the "Run workflow" button — the
`workflow_dispatch` trigger.)

Open <https://github.com/dariogguillen/chess-backend-java/actions> and
watch the run. The expected order of green check marks:

- **✓ Checkout** — `actions/checkout@v4`.
- **✓ Set up Java 21** — `actions/setup-java@v4`, Maven cache restored
  from previous runs (cold first time, ~30s; warm subsequently, ~5s).
- **✓ Run init.sh** — full compile + unit + integration tests, with
  Testcontainers spinning up Postgres + Redis on the runner (~2–3 min).
- **✓ Configure AWS credentials (OIDC)** — the runner exchanges a
  GitHub-signed JWT for short-lived STS credentials. The step logs
  show "Configured AWS credentials" with no key material printed.
- **✓ Login to Amazon ECR** — `aws-actions/amazon-ecr-login@v2`
  populates the registry URL into `steps.ecr-login.outputs.registry`.
- **✓ Build, tag, and push image to ECR** — `docker build` reuses
  cached layers from the previous push (`docker push` only sends new
  layers). The ECR console at
  <https://us-east-2.console.aws.amazon.com/ecr/repositories> should
  show two tags after this step: `latest` and the new commit SHA.
- **✓ Deploy to EC2 over SSH** — the workflow SSHes in, logs into
  ECR from the host, `docker compose pull` retrieves the new image,
  `docker compose up -d` recreates the `app` container. Redis stays
  untouched because its image hash did not change.
- **✓ Smoke test /api/health** — up to 12 retries at 5 s intervals;
  succeeds as soon as `{"status":"UP"}` appears in the response body.

After the smoke-test step turns green, confirm in a browser that the
production OpenAPI spec reports the public HTTPS server:

```bash
curl -sS https://chess-backend.duckdns.org/v3/api-docs | jq '.servers'
```

> Note: the very first deploy via this workflow is what ships the
> `server.forward-headers-strategy: framework` fix from feature 7.5 to
> production. Before this deploy, `servers[0].url` reads `http://...`
> (the bug); after this deploy, it reads `https://chess-backend.duckdns.org`.
> Subsequent deploys leave the field unchanged.

If the run fails, the step log identifies which stage:

- `./init.sh` failure → look at the Surefire/Failsafe report
  archived as a build artifact; the run is treated like any
  failing local `./init.sh`.
- AWS auth failure (`Could not assume role`) → the OIDC trust
  policy condition is misconfigured (wrong repo, wrong branch),
  or the workflow lacks `permissions: id-token: write`, or the
  `AWS_DEPLOY_ROLE_ARN` secret value is stale.
- ECR push failure → check the ECR repo policy and the GHA role's
  `AmazonEC2ContainerRegistryPowerUser` attachment.
- SSH failure (`Permission denied`) → the `DEPLOY_SSH_KEY` secret
  is missing a trailing newline, or the public key was never
  appended to `deploy@<eip>:.ssh/authorized_keys`.
- Smoke test timeout → SSH into the EC2 and read the app logs:
  `sudo -u deploy docker compose -f /opt/chess/docker-compose.prod.yml logs --tail=100 app`.

---

## Troubleshooting

### cloud-init failed (`cloud-init status` reports `error`)

```bash
sudo tail -n 200 /var/log/cloud-init-output.log
sudo cat /var/log/cloud-init.log | less
```

The most common cause is a transient apt mirror error. Re-run the
failed steps manually, or destroy + recreate the instance with
`terraform taint aws_instance.app && terraform apply`.

### Caddy is not getting a certificate

```bash
sudo journalctl -u caddy -n 200
sudo cat /var/log/caddy/chess-backend.log
```

Verify the DNS A record resolves to the Elastic IP from outside the
instance (`dig +short chess-backend.duckdns.org` from your laptop).
Let's Encrypt needs port 80 reachable for the HTTP-01 challenge — the
EC2 security group already opens it, but a corporate firewall on your
laptop side is irrelevant; what matters is that the public internet
can reach `<eip>:80`.

### The app container is unhealthy

```bash
sudo -u deploy docker compose -f /opt/chess/docker-compose.prod.yml logs app
```

Common causes:

- **Wrong RDS credentials in `.env`** — check `SPRING_DATASOURCE_*`
  values match the Terraform variables.
- **RDS not reachable** — confirm from the EC2 host:
  ```bash
  sudo apt-get install -y postgresql-client
  psql "postgresql://chess:<password>@<rds_address>:5432/chess" -c '\l'
  ```
  If this hangs, the RDS security group ingress is misconfigured.
- **App image is the wrong one** — `sudo -u deploy docker images`
  should list `chess-backend:latest`. If absent, redo step 6.

### Caddy logs

JSON-structured access logs (one request per line):

```bash
sudo tail -f /var/log/caddy/chess-backend.log
```

Systemd unit logs (cert issuance, config reloads):

```bash
sudo journalctl -u caddy -f
```

### Spring Boot application logs

```bash
sudo -u deploy docker compose -f /opt/chess/docker-compose.prod.yml logs -f app
```

### Tear down (when migrating off Free Tier later)

From `infra/`:

```bash
terraform destroy
```

This removes the EC2 instance, RDS instance (with `skip_final_snapshot
= true` so no snapshot is taken — adjust `main.tf` first if you want
one), the ECR repository (must be empty first; `aws ecr
batch-delete-image` if needed), the Elastic IP, security groups, and
the budget alarm. The Duck DNS subdomain is yours to keep or release
in their UI.
