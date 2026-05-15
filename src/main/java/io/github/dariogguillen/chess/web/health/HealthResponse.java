package io.github.dariogguillen.chess.web.health;

import java.time.Instant;

/**
 * Response body for {@code GET /api/health}.
 *
 * @param status application status, currently always {@code "UP"} when the endpoint is reachable
 * @param version build version read from {@code BuildProperties}, or {@code "unknown"} when build
 *     info is not on the classpath
 * @param timestamp instant the response was produced, in UTC
 */
public record HealthResponse(String status, String version, Instant timestamp) {}
