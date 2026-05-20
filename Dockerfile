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
