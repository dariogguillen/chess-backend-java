# Current session

**Status:** session closed.

Feature 7 (`backend-containerize`) was closed on 2026-05-20. The
feature shipped a multi-stage Dockerfile, a local-stack
`docker-compose.yml`, an `application.yml` upgrade with env-var-with-
default pattern, three local-dev workflows in the README, and a
"Deployment artifact" subsection in `docs/architecture.md`. A
late-in-feature follow-up pinned `TestcontainersConfiguration` from
`postgres:latest` / `redis:latest` to `postgres:16` / `redis:7-alpine`
so the test environment tracks the deployment environment. See
`progress/history.md` for the full close entry.

The next feature in `feature_list.json` is `backend-aws-infra`
(priority 7.5), the second of three devops sub-features. It
provisions the AWS Free Tier infrastructure manually: EC2 t3.micro on
Ubuntu, RDS Postgres db.t3.micro, Elastic IP, Security Groups, Duck
DNS subdomain, Caddy as auto-TLS reverse proxy, Redis self-hosted in
a Docker container on the same EC2, AWS Budget alarm at $1/month, and
an ECR repository ready for feature 7.7 to push to. The deploy is
manual the first time so the steps live in a runbook before
automation. The leader will open a plan here once the scope and key
decisions for `backend-aws-infra` are aligned with the user.
