package io.kestra.plugin.hex.projects;

import java.time.Duration;
import java.util.Set;

/**
 * Status classification and output mapping shared by {@link Run} and {@link Trigger}, so both
 * agree on what counts as terminal/failed and build the same {@link Run.Output} shape.
 */
final class HexRuns {
    static final String COMPLETED = "COMPLETED";
    private static final Set<String> FAILURE_STATUSES = Set.of("ERRORED", "KILLED", "UNABLE_TO_ALLOCATE_KERNEL");

    private HexRuns() {
    }

    static boolean isTerminal(String status) {
        return COMPLETED.equals(status) || FAILURE_STATUSES.contains(status);
    }

    static boolean isFailure(String status) {
        return FAILURE_STATUSES.contains(status);
    }

    static Run.Output toOutput(String runId, HexRunResponse start, HexRunStatusResponse status) {
        return Run.Output.builder()
            .runId(runId)
            .runUrl(status.getRunUrl() != null ? status.getRunUrl() : (start != null ? start.getRunUrl() : null))
            .status(status.getStatus())
            .projectVersion(status.getProjectVersion() != null ? status.getProjectVersion() : (start != null ? start.getProjectVersion() : null))
            .startTime(status.getStartTime())
            .endTime(status.getEndTime())
            .elapsedTime(toDuration(status))
            .traceId(status.getTraceId() != null ? status.getTraceId() : (start != null ? start.getTraceId() : null))
            .build();
    }

    // Prefers Hex's own reported elapsedTime, which the API returns in milliseconds (verified against a
    // real run: elapsedTime 16952 matched a 16.952s startTime/endTime delta). Falls back to computing it
    // from startTime/endTime when Hex omits it, and gives up cleanly rather than guessing further.
    private static Duration toDuration(HexRunStatusResponse status) {
        if (status.getElapsedTime() != null) {
            return Duration.ofMillis(Math.round(status.getElapsedTime()));
        }
        if (status.getStartTime() != null && status.getEndTime() != null) {
            return Duration.between(status.getStartTime(), status.getEndTime());
        }
        return null;
    }
}
