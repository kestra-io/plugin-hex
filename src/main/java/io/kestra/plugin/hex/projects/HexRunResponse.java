package io.kestra.plugin.hex.projects;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Value;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

/**
 * Response body of {@code POST /projects/{projectId}/runs}. Hex does not report a `status` field on
 * this response, only on the run status endpoint, so a fresh run's status is fetched right away via
 * {@link HexClient#getRunStatus}.
 */
@Value
@Jacksonized
@SuperBuilder
@JsonIgnoreProperties(ignoreUnknown = true)
class HexRunResponse {
    String projectId;
    String runId;
    String runUrl;
    String runStatusUrl;
    String traceId;
    String projectVersion;
}
