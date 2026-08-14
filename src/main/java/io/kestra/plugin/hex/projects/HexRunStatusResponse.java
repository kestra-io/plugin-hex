package io.kestra.plugin.hex.projects;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Value;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

/**
 * Response body of {@code GET /projects/{projectId}/runs/{runId}}. `status` is kept as a raw string
 * rather than a Java enum: Hex adding a new status value must not break deserialization, only leave
 * that run non-terminal until a recognized status is observed.
 */
@Value
@Jacksonized
@SuperBuilder
@JsonIgnoreProperties(ignoreUnknown = true)
class HexRunStatusResponse {
    String projectId;
    String runId;
    String runUrl;
    String status;
    String projectVersion;
    Instant startTime;
    Instant endTime;
    Double elapsedTime;
    String traceId;
}
