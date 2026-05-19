package io.github.dariogguillen.chess.web.health;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * Response body for {@code GET /api/health}.
 *
 * @param status application status, currently always {@code "UP"} when the endpoint is reachable
 * @param version build version read from {@code BuildProperties}, or {@code "unknown"} when build
 *     info is not on the classpath
 * @param timestamp instant the response was produced, in UTC
 */
public record HealthResponse(
    @Schema(
            description = "Application liveness status; always \"UP\" when reachable.",
            example = "UP")
        String status,
    @Schema(
            description = "Application build version, derived from BuildProperties at boot time.",
            example = "0.0.1-SNAPSHOT")
        String version,
    @Schema(
            description = "Server time at the moment the response was produced, in UTC.",
            example = "2026-05-18T12:34:56Z")
        Instant timestamp) {}
