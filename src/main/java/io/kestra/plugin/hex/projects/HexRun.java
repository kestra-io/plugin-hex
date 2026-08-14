package io.kestra.plugin.hex.projects;

import java.time.Duration;
import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A single Hex run, covering both the start response ({@code POST /projects/{id}/runs}) and the
 * status/list response ({@code GET .../runs} and {@code .../runs/{runId}}): their fields overlap enough
 * to share one shape, with fields a given response does not carry simply left null.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record HexRun(
    String projectId,
    String runId,
    String runUrl,
    String runStatusUrl,
    RunStatus status,
    String projectVersion,
    Instant startTime,
    Instant endTime,
    Double elapsedTime,
    String traceId
) {
    // Hex reports elapsedTime in milliseconds (verified against a real run: elapsedTime 16952 matched a
    // 16.952s startTime/endTime delta). Falls back to computing it from startTime/endTime when absent.
    Duration elapsedDuration() {
        if (elapsedTime != null) {
            return Duration.ofMillis(Math.round(elapsedTime));
        }
        if (startTime != null && endTime != null) {
            return Duration.between(startTime, endTime);
        }
        return null;
    }
}
