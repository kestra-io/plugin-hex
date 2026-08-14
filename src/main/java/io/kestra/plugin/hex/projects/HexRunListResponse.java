package io.kestra.plugin.hex.projects;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Value;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

/**
 * Response body of {@code GET /projects/{projectId}/runs}, used by {@link Trigger} to find the most
 * recent completed run without knowing its ID ahead of time. Per Hex's public API reference the
 * envelope wraps the run objects in a {@code runs} array (alongside pagination cursors and a traceId,
 * which are ignored here).
 */
@Value
@Jacksonized
@SuperBuilder
@JsonIgnoreProperties(ignoreUnknown = true)
class HexRunListResponse {
    List<HexRunStatusResponse> runs;
}
