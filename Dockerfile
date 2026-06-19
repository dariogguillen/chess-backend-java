# syntax=docker/dockerfile:1.7
#
# Multi-stage build for the chess-backend-java Spring Boot application.
#
# Stage 1 (builder) uses the full JDK + Maven wrapper to produce the
# repackaged fat jar. The dependency layer is populated before COPY src
# so a code-only change reuses the cached ~/.m2 layer.
#
# Stage 2 (runtime) uses the JRE-only image. It contains nothing but the
# resulting jar and a healthcheck that hits the app's /api/health probe.

# ---- Stage 1: build ----
FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /workspace

# Maven wrapper + POM first so dependency resolution caches independently
# of source changes.
COPY .mvn ./.mvn
COPY mvnw mvnw
COPY pom.xml ./
RUN ./mvnw dependency:go-offline -B

# Now the sources; this layer rebuilds on every code change.
COPY src ./src
RUN ./mvnw package -DskipTests -B

# ---- Stage 2: runtime ----
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Bundle the Fairy-Stockfish chess engine (feature 23 — bot-opponent; strength
# model reworked in feature 23.7 — bot-strength-fairy-stockfish). The bot is
# driven via a subprocess + UCI, so the binary must be present in the runtime
# image. We switched from official Stockfish (apt `stockfish`) to
# Fairy-Stockfish because its `Skill Level` option spans -20..20 — the negative
# levels are what let the bot play below ~1320 Elo, which official Stockfish's
# UCI_Elo floor cannot reach.
#
# Fairy-Stockfish is NOT packaged in apt, so we download a PINNED release asset
# from its GitHub releases instead. Pinned to the `fairy_sf_14` tag, the
# chess-only (non-largeboard) generic `x86-64` build — the plain x86-64 target
# (not -bmi2/-modern) runs on any x86-64 CPU including the t3.micro. The SHA-256
# is verified so the build is reproducible and a tampered/partial download fails
# fast. The binary is ~2.3 MB (smaller than the apt stockfish footprint, and we
# no longer pull in software-properties-common to enable multiverse). We run
# with `Use NNUE false` (classical eval) at runtime, so NO NNUE net file needs
# to be shipped. Documented in notes/23.7-bot-strength-fairy-stockfish.md.
ARG FAIRY_SF_TAG=fairy_sf_14
ARG FAIRY_SF_SHA256=ab6b85823152e78654092dc2fbb154956a559c6ef0455d728268544390ee150f
ADD --chmod=755 \
  https://github.com/fairy-stockfish/Fairy-Stockfish/releases/download/${FAIRY_SF_TAG}/fairy-stockfish_x86-64 \
  /usr/local/bin/fairy-stockfish
RUN echo "${FAIRY_SF_SHA256}  /usr/local/bin/fairy-stockfish" | sha256sum -c -

# Copy the repackaged fat jar (the *.jar.original sibling is the
# unpackaged artifact and is intentionally excluded by glob ordering —
# only the executable jar matches both the COPY and the ENTRYPOINT
# expectations because spring-boot:repackage names it without the
# .original suffix).
COPY --from=builder /workspace/target/chess-*.jar app.jar

EXPOSE 8080

# curl is shipped in eclipse-temurin:21-jre-jammy; if a future base
# image change drops it, install it explicitly here.
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD curl -fsS http://localhost:8080/api/health || exit 1

# Array form so SIGTERM from `docker stop` reaches the JVM directly.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
