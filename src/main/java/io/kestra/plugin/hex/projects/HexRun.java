package io.kestra.plugin.hex.projects;

import java.time.Duration;
import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** A single Hex run, used for both the start response and the status/list response. */
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
    // Hex reports elapsedTime in milliseconds. Falls back to startTime/endTime when absent.
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
