# Deployment runbook

This runbook is the canonical procedure for deploying `chess-backend-java`
to AWS Free Tier. It covers the first-time manual deploy that feature 7.5
delivers; feature 7.7 will automate steps 3 through 9 via GitHub Actions
+ OIDC + ECR.

The live URL is `https://chess-backend.duckdns.org`.

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
