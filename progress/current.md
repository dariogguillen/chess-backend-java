# Current session

**Feature:** `backend-containerize` (priority 7)
**Status:** plan pending approval

---

## Context

This is the first of three sub-features that bring the backend to a
deployable state on AWS Free Tier (12 months) — split per the user's
explicit decision to favour smaller, individually-shippable units:

- **7 `backend-containerize`** (this one) — Dockerfile + local
  docker-compose.
- **7.5 `backend-aws-infra`** — AWS provisioning (EC2 + RDS + Caddy
  + Duck DNS) and first manual deploy.
- **7.7 `backend-cicd-pipeline`** — GitHub Actions with OIDC + ECR +
  automated deploy.

Feature 10 (`docker-compose`) becomes redundant and will be
rescinded when we reach it; feature 12 (`github-actions-ci`)
re-scopes to PR-only validation when 7.7 lands.

## Why this feature, and why now

The backend has been running locally via `./mvnw spring-boot:test-run`
since feature 1 — fine for development, but not a deployment
artifact. Containerization gives us:

- A **portable artifact** (Docker image) that runs identically
  everywhere — local, staging, production.
- A **local-stack workflow** via `docker compose up` for testing
  with real Postgres + Redis (not Testcontainers), useful when
  features 8 and 9 land.
- A **declared dependency surface** in `Dockerfile` + `docker-compose.yml`
  — what the app needs to run, in a self-documenting form.

This feature ships those three things and only those. No AWS, no
CI/CD, no production secrets. Pure containerization.

## Approach

### Dockerfile — multi-stage

Two stages:

```dockerfile
# Stage 1: build with full JDK + Maven wrapper
FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /workspace
COPY .mvn ./.mvn
COPY mvnw mvnw
COPY pom.xml ./
RUN ./mvnw dependency:go-offline -B
COPY src ./src
RUN ./mvnw package -DskipTests -B

# Stage 2: runtime with JRE only
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=builder /workspace/target/*.jar app.jar
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD curl -fsS http://localhost:8080/api/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

Notes:

- `dependency:go-offline` runs before `COPY src` so the Maven
  dependency layer caches across rebuilds — only re-runs when
  `pom.xml` changes.
- `eclipse-temurin:21-jre-jammy` is the chosen runtime base
  (mainstream, well-maintained, ~200MB). Alpine is smaller but has
  glibc quirks; distroless is more secure but lacks `curl` for the
  healthcheck.
- `HEALTHCHECK` uses `curl` against `/api/health`. The runtime base
  includes it. The `start-period=60s` is the Spring Boot warmup
  budget; we are well within it on a t3.micro.
- `EXPOSE 8080` is documentation only — the actual port mapping
  happens in `docker-compose.yml` or via `docker run -p`.

### `.dockerignore`

Excludes everything the build does not need:

```
target/
.git/
.gitignore
.gitattributes
.mvn/wrapper/maven-wrapper.jar
.idea/
.vscode/
*.iml
.DS_Store
notes/
progress/
docs/
.claude/
CHECKPOINTS.md
CLAUDE.md
AGENTS.md
```

(Maven wrapper jar is regenerated; do not include the editor
artifacts; exclude harness directories from the build context to
keep it small.)

### `docker-compose.yml` — local development stack

```yaml
services:
  postgres:
    image: postgres:16
    environment:
      POSTGRES_USER: chess
      POSTGRES_PASSWORD: chess
      POSTGRES_DB: chess
    volumes:
      - postgres_data:/var/lib/postgresql/data
    ports:
      - "5432:5432"
    healthcheck:
      test: ["CMD", "pg_isready", "-U", "chess"]
      interval: 10s
      timeout: 3s
      retries: 5

  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 10s
      timeout: 3s
      retries: 5

  app:
    build: .
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_healthy
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/chess
      SPRING_DATASOURCE_USERNAME: chess
      SPRING_DATASOURCE_PASSWORD: chess
      SPRING_DATA_REDIS_HOST: redis
      SPRING_DATA_REDIS_PORT: 6379
    ports:
      - "8080:8080"

volumes:
  postgres_data:
```

Notes:

- `depends_on` with `service_healthy` ensures the app does not start
  until Postgres and Redis are actually ready, not just process-up.
- The app service hostname matches the service name (`postgres`,
  `redis`), so Spring connects to `postgres:5432` from inside the
  docker network.
- Postgres data persists in a named volume so restarts do not lose
  state.
- Host port mappings let tools on the host (psql, redis-cli, the
  local Spring Boot run in IDE) reach the containers.

### `application.yml` — env-var-with-default

Today's `application.yml` is minimal (one line: `spring.application.name`).
Add env-var-with-default config for the datasource and Redis so the
same artifact runs in three contexts:

```yaml
spring:
  application:
    name: chess
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/chess}
    username: ${SPRING_DATASOURCE_USERNAME:chess}
    password: ${SPRING_DATASOURCE_PASSWORD:chess}
  data:
    redis:
      host: ${SPRING_DATA_REDIS_HOST:localhost}
      port: ${SPRING_DATA_REDIS_PORT:6379}
```

Important: this is purely additive. The `TestcontainersConfiguration`
in `src/test/java/.../TestcontainersConfiguration.java` uses
`@ServiceConnection` which **overrides** these properties at
runtime — Testcontainers tests are unaffected. The defaults
(`localhost:5432`, `localhost:6379`) match what someone running the
app outside Docker against locally-installed Postgres/Redis would
expect. docker-compose sets the env vars explicitly and connects to
the in-network hostnames.

## Files to create

```
Dockerfile                       (new; multi-stage)
.dockerignore                    (new)
docker-compose.yml               (new; local-dev stack)
notes/07-backend-containerize.md (new; feature note)
```

## Files to modify

- `src/main/resources/application.yml`:
  - Add `spring.datasource.*` block with `${ENV_VAR:default}` for
    url, username, password.
  - Add `spring.data.redis.host` and `spring.data.redis.port` with
    `${ENV_VAR:default}`.
  - Verify no behavior change in existing Testcontainers ITs —
    `@ServiceConnection` keeps overriding at test runtime.

- `README.md`:
  - Add new section **"Running locally"** (or extend existing) with
    three sub-sections:
    1. **With Testcontainers** (existing, primary dev workflow):
       `./mvnw spring-boot:test-run`. Fast, no Docker image build,
       Testcontainers manages infra.
    2. **With docker-compose** (new): `docker compose up --build`.
       Production-like stack, slower first build (image build).
       Useful for testing as-prod scenarios.
    3. **Hybrid** (new): `docker compose up postgres redis` for
       infra only + `./mvnw spring-boot:run` for the app, connecting
       via the host port mappings (5432, 6379). Useful for debugging
       with the IDE attached to the app process.
  - One paragraph each, one paste-and-run command per workflow.

- `docs/architecture.md`:
  - Add a short subsection under "Layered architecture" or as a new
    "Deployment artifact" subsection (your call) mentioning the
    container as the deployment artifact, the multi-stage build
    approach, and the env-var-with-default pattern that decouples
    the artifact from the environment.

## Files unchanged

- `pom.xml` — no new dependencies. Spring Boot's `build-info` and
  `spring-boot:repackage` (already wired in feature 1) produce the
  fat jar the Dockerfile copies.
- `init.sh` — no Docker steps added here. Verification of Docker
  build + compose-up is **manual** in this feature (see "Verification"
  below) and **automated** in feature 7.7's CI smoke test. Adding
  `docker build` to `init.sh` would slow local iteration without
  added safety in this scope.
- `src/main/java/**`, `src/test/java/**` — no code change. The
  application is already container-ready by virtue of being a Spring
  Boot fat jar that listens on a configurable port.
- `.gitignore` — already covers `target/`. No update needed.

## Verification

**Automated (existing pipeline, must remain green):**

- `./init.sh` exits 0 unchanged. Surefire 82, Failsafe 30, total
  112. No new tests, no regressions, no skipped tests.

**Manual (executed by implementer, documented in the report):**

1. `docker compose up --build` succeeds. First build takes ~2-3
   minutes (downloads base images + Maven dependencies). Subsequent
   builds use the cached dependency layer and are fast (~30s).
2. Once the stack is up, `docker compose ps` shows all three
   services healthy. The Postgres and Redis healthchecks pass; the
   app's `HEALTHCHECK` succeeds within the 60-second start period.
3. `curl -fsS http://localhost:8080/api/health` returns 200 with
   the expected JSON shape (`status`, `version`, `timestamp`).
4. `curl -fsS http://localhost:8080/v3/api-docs | jq '.paths | keys'`
   shows the documented REST paths from prior features.
5. `docker compose down -v` cleans up containers and volumes.

**Manual variations (to document but not block):**

- Test the hybrid workflow: `docker compose up postgres redis` +
  `./mvnw spring-boot:run`. App should boot connecting to the
  containers via host ports. Confirms the env-var defaults work.
- Test the Testcontainers workflow still works:
  `./mvnw spring-boot:test-run`. Confirms `@ServiceConnection`
  overrides did not break.

## Java / Spring concepts to add to `notes/07-backend-containerize.md`

- **Multi-stage Docker builds**: builder stage vs runtime stage,
  dependency-layer caching, JDK-vs-JRE base image distinction. Why
  the JRE-only runtime image is significantly smaller and reduces
  attack surface.
- **Spring Boot fat jar**: the executable archive produced by
  `spring-boot:repackage` (enabled by the Spring Boot Maven plugin).
  Compare with WAR deployment to an external Tomcat (the old
  pattern) and with native images via Spring AOT (the new pattern,
  not used here).
- **`@ServiceConnection` vs `application.yml`**: how Testcontainers
  cooperates with Spring's autoconfig; why the docker-compose env
  vars do not collide with test runs.
- **`HEALTHCHECK` directive in Docker**: how Docker uses it for
  status reporting, how `depends_on: condition: service_healthy`
  composes services that wait for actual readiness rather than
  process-up.
- **`.dockerignore`**: build-context hygiene; how a fat
  `.dockerignore` keeps the daemon transfer fast and the cache
  effective.

## Decisions to surface in the note

- **Eclipse Temurin JRE Jammy** as runtime base. Alternatives
  considered: Alpine (smaller, glibc quirks), distroless (more
  secure, no shell). Why Jammy: mainstream Spring Boot doc support,
  `curl` available for healthcheck, easy to extend with `apt-get`
  if needed.
- **Multi-stage build vs Spring Boot's `bootBuildImage` /
  Cloud Native Buildpacks**: buildpacks produce optimized layered
  images without writing a Dockerfile. Considered and rejected for
  this feature because writing the Dockerfile is the **learning
  point**; buildpacks are "magic". A future cleanup could swap to
  buildpacks once the team is comfortable with the manual approach.
- **Three local-dev workflows documented in README**: rather than
  forcing one canonical path. Each has a clear "when to use it" so
  the developer picks the right tool for the task.
- **No Docker steps added to `init.sh`**: deliberately scope `init.sh`
  to "compile + lint + test" — Docker build is deployment-adjacent.
  Feature 7.7's CI workflow will add a smoke test that runs the
  containerized app against a real Postgres/Redis in GitHub
  Actions infra.

## Public API and architecture impact

- **Public API change (README)?** **Yes** — the README gains the
  "Running locally" section with three workflows. No HTTP API
  change; the spec is unchanged.
- **Architectural decision (`docs/architecture.md`)?** **Yes** —
  the deployment artifact shape (container) is a new architectural
  decision worth recording. Short paragraph, not a new top-level
  section.
- **Cross-repo coordination?** **No.** Frontend is unaffected; the
  backend's container is internal infrastructure.

## Out of scope

- AWS provisioning. That is feature 7.5.
- ECR push, OIDC auth, GitHub Actions deploy workflow. That is
  feature 7.7.
- Production environment variables, secrets management. Until
  features 7.5/7.7, only local docker-compose values exist.
- Multi-architecture images (`linux/arm64` for Apple Silicon).
  Default `docker build` produces `linux/amd64`; if a contributor on
  ARM hits issues, we revisit then.
- Image size optimization beyond multi-stage (JLink to build a
  custom JRE, native images). Diminishing returns at this scale.
- Replacing Testcontainers in tests with docker-compose. Testcontainers
  remains the test-time mechanism; docker-compose is the local-run
  mechanism. Different layers.
- Updating CI to run Docker build. Feature 7.7 owns CI changes.

## Definition of done

All applicable items in `CHECKPOINTS.md` pass. Specific to this
feature:

- `Dockerfile` exists, is multi-stage, includes `HEALTHCHECK`.
- `.dockerignore` exists and excludes the harness + IDE +
  build-artifact directories listed above.
- `docker-compose.yml` exists, brings up the three services with
  health-aware dependencies, exposes the expected host ports.
- `application.yml` uses env-var-with-default for datasource and
  Redis without breaking Testcontainers tests.
- README has the three-workflow "Running locally" section.
- `docs/architecture.md` has the deployment-artifact paragraph.
- `notes/07-backend-containerize.md` exists and follows
  `notes/_template.md`.
- `./init.sh` passes unchanged.
- Manual verification (the 5 steps above) executed by the
  implementer and reported.

After reviewer approval, the leader reports to the user and **waits
for the user's explicit OK** before flipping `feature_list.json` to
`done` and rolling the next sub-feature (7.5 `backend-aws-infra`).
