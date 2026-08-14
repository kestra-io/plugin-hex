package io.kestra.plugin.hex.projects;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Response body of {@code GET /projects/{projectId}/runs}, used to find the most recent completed run
 * without knowing its ID ahead of time. Per Hex's public API reference the envelope wraps the run
 * objects in a {@code runs} array (alongside pagination cursors and a traceId, which are ignored here).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record HexRunList(List<HexRun> runs) {
}
