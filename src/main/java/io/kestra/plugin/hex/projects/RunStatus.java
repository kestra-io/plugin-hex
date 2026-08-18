package io.kestra.plugin.hex.projects;

import java.util.Set;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * Status of a Hex run. Unrecognized values map to {@link #UNKNOWN} rather than failing deserialization.
 */
enum RunStatus {
    PENDING,
    RUNNING,
    ERRORED,
    COMPLETED,
    KILLED,
    UNABLE_TO_ALLOCATE_KERNEL,
    UNKNOWN;

    private static final Set<RunStatus> FAILURE_STATUSES = Set.of(ERRORED, KILLED, UNABLE_TO_ALLOCATE_KERNEL);

    @JsonCreator
    static RunStatus fromValue(String value) {
        try {
            return RunStatus.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            return UNKNOWN;
        }
    }

    boolean isTerminal() {
        return this == COMPLETED || isFailure();
    }

    boolean isFailure() {
        return FAILURE_STATUSES.contains(this);
    }
}
