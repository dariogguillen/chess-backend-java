# Feature 07 — Containerize the backend

**Feature ID:** `backend-containerize` (from `feature_list.json`)

**Status:** in progress

---

## What we built

A self-contained deployment artifact for the backend. A multi-stage
`Dockerfile` produces a JRE-only runtime image of the Spring Boot fat
jar, a `docker-compose.yml` brings up the full local stack (app +
Postgres 16 + Redis 7-alpine) with health-aware dependencies, and
`application.yml` switches to an env-var-with-default pattern so the
same image runs under three workflows: Testcontainers in tests,
`docker compose up` locally, and (in features 7.5 / 7.7) production.
The README documents the three workflows side by side; nothing in the
HTTP / WebSocket contract changes.

## Java / Spring concepts that appear

- **Multi-stage Docker builds**. The `Dockerfile` declares two stages
  — `eclipse-temurin:21-jdk-jammy AS builder` for compilation and
  `eclipse-temurin:21-jre-jammy` for runtime. Only the second stage
  becomes the published image; the JDK + Maven wrapper + `~/.m2` cache
  + intermediate class files all stay in the builder layer and are
  thrown away. Layer ordering matters: `COPY pom.xml` + `dependency:go-offline`
  precedes `COPY src` so a code-only change reuses the
  cached dependency layer. See the
  [Docker multi-stage builds reference](https://docs.docker.com/build/building/multi-stage/).

- **Spring Boot fat jar via `spring-boot:repackage`**. The Spring Boot
  Maven plugin (already wired in `pom.xml` since feature 1) takes the
  thin jar Maven would normally produce and "repackages" it: it nests
  all runtime dependency jars under `BOOT-INF/lib/` and wires a custom
  `Main-Class` that bootstraps them. The resulting `target/chess-*.jar`
  is the single executable artifact our Dockerfile copies into the
  runtime stage. Compare this with the older WAR-deployed-to-Tomcat
  pattern (the WAR is half the application; the servlet container is
  the other half) and the newer GraalVM native-image pattern (single
  static binary, no JVM at runtime — not used here, since the Spring
  Boot fat jar already meets the portability bar we need). See the
  [Spring Boot Maven plugin docs — packaging executable
  archives](https://docs.spring.io/spring-boot/maven-plugin/packaging.html).

- **`@ServiceConnection` precedence over `application.yml`**. Tests
  use `TestcontainersConfiguration`, which declares the Postgres and
  Redis containers as `@Bean`s annotated `@ServiceConnection`. Spring
  Boot's `ServiceConnection` machinery registers a
  `JdbcConnectionDetails` / `RedisConnectionDetails` bean that
  **overrides** the `spring.datasource.*` / `spring.data.redis.*`
  properties at runtime. That is what makes this feature's
  `application.yml` change safe: the defaults
  (`localhost:5432`, `localhost:6379`) are advisory, the env vars
  override them in docker-compose and production, and
  `@ServiceConnection` overrides everything in tests. Three contexts,
  one artifact. See the
  [Spring Boot reference — connecting to services with
  ServiceConnection](https://docs.spring.io/spring-boot/reference/testing/testcontainers.html#testing.testcontainers.service-connections).

- **`HEALTHCHECK` directive composing with `depends_on: service_healthy`**.
  The runtime stage declares `HEALTHCHECK ... CMD curl -fsS
  http://localhost:8080/api/health` with a 60s `start-period` (Spring
  Boot warmup budget on a t3.micro). In `docker-compose.yml`, the
  `app` service's `depends_on` block uses
  `condition: service_healthy` against `postgres` and `redis`, so
  the app process is not started until both dependencies report
  healthy via their own probes (`pg_isready`, `redis-cli ping`). The
  result is that `docker compose up` never produces the
  "app boots, tries to connect, fails, restarts" thrash that an
  un-coordinated stack would. See the
  [Docker `HEALTHCHECK`
  reference](https://docs.docker.com/reference/dockerfile/#healthcheck)
  and the
  [Compose `depends_on`
  reference](https://docs.docker.com/reference/compose-file/services/#depends_on).

- **`.dockerignore` and build-context hygiene**. The Docker daemon
  receives the entire build context as a tarball before any
  `Dockerfile` instruction runs. A fat `.dockerignore` keeps that
  transfer fast (no `target/`, no `.git/`, no harness directories)
  and prevents accidental cache-busts (a file change in `notes/` does
  not invalidate any layer because `notes/` is not in the context at
  all). See the
  [Docker `.dockerignore`
  reference](https://docs.docker.com/build/concepts/context/#dockerignore-files).

## Decisions taken

**Eclipse Temurin JRE Jammy as runtime base.**

- Decision: `eclipse-temurin:21-jre-jammy` for the runtime stage,
  `eclipse-temurin:21-jdk-jammy` for the builder stage.
- Alternatives: Alpine variants (`eclipse-temurin:21-jre-alpine`) for
  smaller image size; Google distroless (`gcr.io/distroless/java21`)
  for minimal attack surface.
- Why: Jammy is the mainstream choice for Spring Boot reference
  documentation and StackOverflow answers, which matters when the
  point of this exercise is learning the Java ecosystem rather than
  hyper-optimizing. Alpine uses musl libc, which surfaces subtle JNI
  compatibility issues with some libraries; distroless lacks `curl`
  and a shell, which would force a different healthcheck strategy
  (Spring Boot Actuator's `/actuator/health` polled by something
  external). Jammy ships `curl` out of the box (verified by
  `docker run --rm eclipse-temurin:21-jre-jammy bash -c "which curl"`)
  so the `HEALTHCHECK CMD curl ...` directive Just Works.

**Hand-written Dockerfile, not Cloud Native Buildpacks.**

- Decision: declare the multi-stage `Dockerfile` explicitly.
- Alternatives: Spring Boot's `spring-boot:build-image` goal (powered
  by Cloud Native Buildpacks via Paketo) produces an optimized,
  layered image without any Dockerfile at all; `jib-maven-plugin`
  (Google) does the same daemonless.
- Why: the Dockerfile **is** the learning material here. Buildpacks
  are great in production but they hide the layering, the
  cache strategy, and the base-image choice behind defaults — exactly
  the things a Java newcomer benefits from spelling out. Once the
  team is comfortable, swapping to `bootBuildImage` is a small,
  reversible change that we can revisit when feature 7.7's CI lands.

**Three local-dev workflows in the README, not one canonical path.**

- Decision: README documents Testcontainers,
  `docker compose up`, and the hybrid mode side by side.
- Alternatives: pick one as the "official" workflow and document
  only that; document one in the README and the rest in a wiki page;
  bury the alternatives in `notes/`.
- Why: each workflow has a clear "when to use it" — Testcontainers
  for fast inner-loop iteration, Compose for as-prod validation
  before opening a PR, hybrid for IDE-attached debugging. Forcing
  one path would either slow everyday development (Compose for tiny
  edits) or hide an important deployment-shape sanity check
  (Testcontainers only). Documenting all three makes the trade-offs
  explicit so the developer picks the right tool.

**No Docker steps in `init.sh`.**

- Decision: `init.sh` continues to be "compile + lint + test".
  Building / running the image is verified manually in this feature
  and automated in feature 7.7's CI.
- Alternatives: add `docker build -t chess-backend-java .` and a
  smoke-up step to `init.sh`; add an optional `--docker` flag.
- Why: `init.sh` is the verification gate that every change passes
  through, and Docker builds are slow (~30s cached, ~3min cold)
  relative to a Maven incremental compile. Slowing the inner loop
  for safety we already get from Testcontainers tests would be a
  net negative. Feature 7.7 puts the Docker smoke test where it
  belongs: in CI, parallel to (not blocking) the existing test
  pipeline.

## How this compares to what I know

- **In Scala / Typelevel — sbt-native-packager + Docker plugin.**
  `sbt-native-packager`'s `JavaAppPackaging` + `DockerPlugin`
  produces a Docker image from your application config without
  writing a Dockerfile, roughly equivalent to Spring Boot's
  `bootBuildImage`. The trade-off is symmetric: easier defaults at
  the cost of opaque layering. The Scala ecosystem also has `sbt-docker`
  for hand-written Dockerfiles when you outgrow native-packager.
  Picking the hand-written Dockerfile here is the same decision a
  Scala team makes when they want to control the base image and
  layer order explicitly.

- **In Scala / Typelevel — `jib`.** Google's `sbt-jib` plugin
  (mirroring `jib-maven-plugin` for Maven) is the
  daemonless option: it pushes the image straight to a registry by
  composing layers from your classpath, without invoking `docker
  build` locally. The conceptual parallel in the Spring world is
  exactly `jib-maven-plugin`. We considered and rejected it for
  the same reason we rejected buildpacks: the Dockerfile is the
  point of this feature.

- **In Node — `node:21-alpine` + `npm ci`.** A typical Node
  Dockerfile is `FROM node:21-alpine`, `COPY package*.json`, `RUN npm
  ci`, `COPY . .`, with the dependency-cache layer trick mirroring
  what `dependency:go-offline` does for Maven. The fundamental shape
  is the same — separate dep-resolution from source — only the
  package manager invocation changes.

- **`@ServiceConnection` vs sbt `Test / fork := true` + sys-prop
  overrides.** The Scala equivalent of `@ServiceConnection`'s
  property-override mechanism is what you do manually in sbt:
  `Test / javaOptions ++= Seq("-Dspring.datasource.url=...")`. Spring
  Boot has automated the wiring: declare a `@TestConfiguration` with
  a Testcontainers `@Bean`, mark it `@ServiceConnection`, and the
  override is set for you. That is the kind of "Spring does
  ceremonial wiring so you don't" that I am still calibrating to —
  in Typelevel-land, the explicit override is part of the test
  harness; in Spring, it is a single annotation.

## Gotchas / things I learned the hard way

- **`target/` in the host vs builder-stage `target/`**. My first
  mental model was "`.dockerignore` excludes `target/`, so where does
  the jar come from?" The answer is that the builder stage runs `mvn
  package` *inside the container*, producing its own `target/` under
  `/workspace/target/`. The runtime stage's `COPY --from=builder
  /workspace/target/chess-*.jar` reads from the builder filesystem,
  not the host. Excluding the host's `target/` is correct because
  including it would let a stale local jar contaminate the image
  silently.

- **`curl` is not guaranteed in JRE base images**. The plan called
  this out and I verified it. `eclipse-temurin:21-jre-jammy` does
  ship `curl`, but `eclipse-temurin:21-jre-alpine` does not (`apk
  add --no-cache curl` would be required). The verification
  command was `docker run --rm eclipse-temurin:21-jre-jammy bash -c
  "which curl"`. If the base image ever drops `curl`, the
  `HEALTHCHECK` will silently fail to "unhealthy" and `depends_on:
  service_healthy` will block downstream consumers.

- **Compose v2 deprecates the `version:` field**. Modern
  `docker-compose.yml` files start with `services:` directly. Older
  examples still show `version: "3.8"` at the top. The local Docker
  installation here is Docker 29.4.3 / Compose 5.1.3 — both modern
  enough that the `version:` field is at best ignored and at worst
  emits a deprecation warning.

- **`spring-boot:test-run` is the fast workflow, but it requires the
  test configuration to declare `@ServiceConnection`-marked
  containers**. The `TestcontainersConfiguration` class was already
  there from feature 1; this feature did not need to touch it. A
  future-me who refactors the test container wiring should be aware
  that `spring-boot:test-run` reads from `src/test/java/...`'s
  `@TestConfiguration` beans.

## To dig deeper

- [Docker multi-stage
  builds](https://docs.docker.com/build/building/multi-stage/) — the
  official reference, with examples for Java applications.
- [Spring Boot Maven plugin —
  packaging](https://docs.spring.io/spring-boot/maven-plugin/packaging.html)
  — what `spring-boot:repackage` actually does.
- [Spring Boot Testcontainers service
  connections](https://docs.spring.io/spring-boot/reference/testing/testcontainers.html#testing.testcontainers.service-connections)
  — how `@ServiceConnection` overrides at runtime.
- [Compose file `depends_on` with
  conditions](https://docs.docker.com/reference/compose-file/services/#depends_on)
  — `service_healthy`, `service_started`, and what they actually
  mean.
- [Eclipse Temurin official image
  page](https://hub.docker.com/_/eclipse-temurin) — the variants and
  what's in each (the Jammy variants are the Ubuntu 22.04 base).

## File map

New files:

- `Dockerfile` — multi-stage build. Builder stage (JDK + mvnw)
  resolves dependencies, then packages the fat jar. Runtime stage
  (JRE) copies the jar in, declares a `curl`-based `HEALTHCHECK`
  against `/api/health`, and uses array-form `ENTRYPOINT` so signals
  propagate.
- `.dockerignore` — excludes `target/`, `.git/`, IDE metadata,
  harness directories (`.claude/`, `progress/`, `notes/`, `docs/`),
  and editor files from the build context.
- `docker-compose.yml` — local development stack. Postgres 16 +
  Redis 7-alpine + the app, with health-aware dependencies, host
  port mappings (5432, 6379, 8080), and a named volume for Postgres
  durability across restarts.
- `notes/07-backend-containerize.md` — this file.

Modified files:

- `src/main/resources/application.yml` — added
  `spring.datasource.{url,username,password}` and
  `spring.data.redis.{host,port}` with `${ENV_VAR:default}` defaults.
  Tests are unaffected because `@ServiceConnection` overrides these
  at runtime.
- `README.md` — replaced the "Running locally" placeholder with three
  workflow subsections (Testcontainers, docker-compose, hybrid),
  each with a one-paragraph rationale and a paste-and-run command.
- `docs/architecture.md` — added a "Deployment artifact" subsection
  under "Layered architecture" describing the multi-stage Docker
  image, the env-var-with-default pattern, and the explicit decision
  to keep `init.sh` Docker-free.

Unchanged but cross-referenced:

- `pom.xml` — no new dependencies. The Spring Boot Maven plugin's
  `spring-boot:repackage` (wired since feature 1) produces the fat
  jar the Dockerfile copies.
- `src/test/java/io/github/dariogguillen/chess/TestcontainersConfiguration.java`
  — the `@ServiceConnection`-annotated beans that override the new
  `application.yml` defaults at test runtime. Verified by running
  `./init.sh` after the application.yml change.
- `init.sh` — deliberately untouched. Feature 7.7's CI workflow
  will add the Docker smoke test.
