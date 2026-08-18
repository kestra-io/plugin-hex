package io.kestra.plugin.hex.projects;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.HttpClientException;
import io.kestra.core.http.client.HttpClientResponseException;
import io.kestra.core.http.client.configurations.BearerAuthConfiguration;
import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;

/**
 * Hex REST API connection shared by {@link Run} and {@link Trigger}. Hex ships no Java SDK, so every
 * call goes through Kestra's own HTTP client rather than a third-party dependency.
 *
 * A task and a trigger cannot share a superclass (one extends Task, the other AbstractTrigger), so
 * this interface carries the connection behaviour as default methods instead: both implementors only
 * need to expose {@link #getApiToken()} and {@link #getBaseUrl()}, which their existing Lombok
 * {@code @Getter} already provides.
 */
interface HexConnectionInterface {
    String DEFAULT_BASE_URL = "https://app.hex.tech/api/v1";

    Property<String> getApiToken();

    Property<String> getBaseUrl();

    default HexRun startRun(RunContext runContext, String projectId, Map<String, Object> inputParams)
        throws IOException, IllegalVariableEvaluationException, HttpClientException {
        var requestBuilder = HttpRequest.builder()
            .uri(URI.create(baseUrl(runContext) + "/projects/" + encode(projectId) + "/runs"))
            .method("POST")
            .body(
                HttpRequest.JsonRequestBody.builder()
                    .content(inputParams == null || inputParams.isEmpty() ? Map.of() : Map.of("inputParams", inputParams))
                    .build()
            );

        return parse(request(runContext, requestBuilder), HexRun.class, "start a run for project '" + projectId + "'");
    }

    default HexRun getRun(RunContext runContext, String projectId, String runId)
        throws IOException, IllegalVariableEvaluationException, HttpClientException {
        var requestBuilder = HttpRequest.builder()
            .uri(URI.create(baseUrl(runContext) + "/projects/" + encode(projectId) + "/runs/" + encode(runId)))
            .method("GET");

        return parse(request(runContext, requestBuilder), HexRun.class, "get the status of run '" + runId + "'");
    }

    default Optional<HexRun> latestCompletedRun(RunContext runContext, String projectId)
        throws IOException, IllegalVariableEvaluationException, HttpClientException {
        var requestBuilder = HttpRequest.builder()
            .uri(URI.create(baseUrl(runContext) + "/projects/" + encode(projectId) + "/runs?limit=1&statusFilter=COMPLETED"))
            .method("GET");

        var list = parse(request(runContext, requestBuilder), HexRunList.class, "list recent runs for project '" + projectId + "'");
        return list.runs() == null || list.runs().isEmpty() ? Optional.empty() : Optional.of(list.runs().getFirst());
    }

    default void cancelRun(RunContext runContext, String projectId, String runId)
        throws IOException, IllegalVariableEvaluationException, HttpClientException {
        var requestBuilder = HttpRequest.builder()
            .uri(URI.create(baseUrl(runContext) + "/projects/" + encode(projectId) + "/runs/" + encode(runId)))
            .method("DELETE");

        request(runContext, requestBuilder);
    }

    private String baseUrl(RunContext runContext) throws IllegalVariableEvaluationException {
        return runContext.render(this.getBaseUrl()).as(String.class).orElse(DEFAULT_BASE_URL);
    }

    private HttpResponse<String> request(RunContext runContext, HttpRequest.HttpRequestBuilder requestBuilder)
        throws IOException, IllegalVariableEvaluationException, HttpClientException {
        var request = requestBuilder
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .build();

        var httpConfiguration = HttpConfiguration.builder()
            .auth(BearerAuthConfiguration.builder().token(this.getApiToken()).build())
            .build();

        try (HttpClient client = new HttpClient(runContext, httpConfiguration)) {
            return client.request(request, String.class);
        } catch (HttpClientResponseException e) {
            throw translate(e);
        }
    }

    // Surfaces Hex's own error message rather than a bare status code or hardcoded guess. Hex's body is
    // usually the most precise explanation ("This project has not been published" on a 422, "Invalid
    // project ID" on a 400), so there is nothing to add on top of it.
    private static HttpClientResponseException translate(HttpClientResponseException e) {
        int code = e.getResponse().getStatus().getCode();
        String message = hexMessage(e.getResponse().getBody());
        String detail = message != null ? message : "the Hex API returned an unexpected response";
        return new HttpClientResponseException("Hex API call failed with status " + code + ": " + detail, e.getResponse(), e);
    }

    // Pulls the reason out of Hex's error body: the structured JSON envelope ({"message", "issues"}) when
    // present, otherwise the raw body text (for example the plain "Unauthorized" on an auth failure).
    // Returns null only for a missing or blank body, and caps the length so an unexpected large body
    // cannot bloat the error.
    private static String hexMessage(Object body) {
        if (body == null) {
            return null;
        }
        // Error responses come back as raw bytes, so decode those rather than stringifying the array reference.
        String text = body instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : body.toString();
        if (text.isBlank()) {
            return null;
        }
        try {
            JsonNode node = JacksonMapper.ofJson().readTree(text);
            var sb = new StringBuilder();
            if (node.hasNonNull("message")) {
                sb.append(node.get("message").asText());
            }
            JsonNode issues = node.get("issues");
            if (issues != null && issues.isArray()) {
                for (JsonNode issue : issues) {
                    if (issue.hasNonNull("message")) {
                        sb.append(sb.isEmpty() ? "" : "; ").append(issue.get("message").asText());
                    }
                }
            }
            if (!sb.isEmpty()) {
                return sb.toString();
            }
        } catch (Exception ignored) {
            // Not JSON, fall back to the raw body text below.
        }
        return text.strip().length() > 500 ? text.strip().substring(0, 500) : text.strip();
    }

    private static <T> T parse(HttpResponse<String> response, Class<T> type, String action) throws IOException {
        var body = response.getBody();
        if (body == null || body.isBlank()) {
            throw new IllegalStateException("Received an empty response from the Hex API while trying to " + action);
        }
        return JacksonMapper.ofJson().readValue(body, type);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    // Response body of GET /projects/{projectId}/runs. Hex wraps the run objects in a "runs" array
    // (alongside pagination cursors and a traceId, which are ignored here).
    @JsonIgnoreProperties(ignoreUnknown = true)
    record HexRunList(List<HexRun> runs) {
    }
}
