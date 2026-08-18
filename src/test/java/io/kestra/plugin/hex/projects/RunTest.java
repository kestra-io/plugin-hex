package io.kestra.plugin.hex.projects;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.stubbing.Scenario;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.utils.IdUtils;
import io.kestra.core.utils.TestsUtils;

import jakarta.inject.Inject;
import jakarta.validation.ConstraintViolationException;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
@WireMockTest
@Execution(ExecutionMode.SAME_THREAD)
class RunTest {

    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void startsAndWaitsUntilCompleted(WireMockRuntimeInfo wm) throws Exception {
        String projectId = "proj-" + IdUtils.create();
        String runId = "run-" + IdUtils.create();
        String scenario = "wait-until-completed";

        stubFor(
            post(urlEqualTo("/projects/" + projectId + "/runs"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(startBody(projectId, runId)))
        );

        stubFor(
            get(urlEqualTo("/projects/" + projectId + "/runs/" + runId))
                .inScenario(scenario)
                .whenScenarioStateIs(Scenario.STARTED)
                .willSetStateTo("COMPLETED")
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(statusBody(projectId, runId, "RUNNING", null, null)))
        );
        stubFor(
            get(urlEqualTo("/projects/" + projectId + "/runs/" + runId))
                .inScenario(scenario)
                .whenScenarioStateIs("COMPLETED")
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(statusBody(projectId, runId, "COMPLETED", "2026-01-01T00:00:00Z", "2026-01-01T00:00:05Z")))
        );

        Run task = Run.builder()
            .id(IdUtils.create())
            .type(Run.class.getName())
            .apiToken(Property.ofValue("dummy-token"))
            .baseUrl(Property.ofValue(wm.getHttpBaseUrl()))
            .projectId(Property.ofValue(projectId))
            .pollFrequency(Property.ofValue(Duration.ofMillis(200)))
            .maxDuration(Property.ofValue(Duration.ofSeconds(10)))
            .build();

        Run.Output output = task.run(runContext(task));

        assertThat(output.getRunId(), is(runId));
        assertThat(output.getStatus(), is("COMPLETED"));
        assertThat(output.getProjectVersion(), is("3"));
        assertThat(output.getElapsedTime(), is(Duration.ofSeconds(5)));
        assertThat(output.getTraceId(), is("trace-1"));

        verify(exactly(1), postRequestedFor(urlEqualTo("/projects/" + projectId + "/runs")));
    }

    @Test
    void failsWithClearMessageWhenRunErrors(WireMockRuntimeInfo wm) throws Exception {
        String projectId = "proj-" + IdUtils.create();
        String runId = "run-" + IdUtils.create();

        stubFor(
            post(urlEqualTo("/projects/" + projectId + "/runs"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(startBody(projectId, runId)))
        );
        stubFor(
            get(urlEqualTo("/projects/" + projectId + "/runs/" + runId))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(statusBody(projectId, runId, "ERRORED", "2026-01-01T00:00:00Z", "2026-01-01T00:00:05Z")))
        );

        Run task = Run.builder()
            .id(IdUtils.create())
            .type(Run.class.getName())
            .apiToken(Property.ofValue("dummy-token"))
            .baseUrl(Property.ofValue(wm.getHttpBaseUrl()))
            .projectId(Property.ofValue(projectId))
            .build();

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> task.run(runContext(task)));
        assertThat(thrown.getMessage(), containsString(runId));
        assertThat(thrown.getMessage(), containsString("ERRORED"));
    }

    @Test
    void returnsImmediatelyWhenWaitIsFalse(WireMockRuntimeInfo wm) throws Exception {
        String projectId = "proj-" + IdUtils.create();
        String runId = "run-" + IdUtils.create();

        stubFor(
            post(urlEqualTo("/projects/" + projectId + "/runs"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(startBody(projectId, runId)))
        );
        stubFor(
            get(urlEqualTo("/projects/" + projectId + "/runs/" + runId))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(statusBody(projectId, runId, "RUNNING", null, null)))
        );

        Run task = Run.builder()
            .id(IdUtils.create())
            .type(Run.class.getName())
            .apiToken(Property.ofValue("dummy-token"))
            .baseUrl(Property.ofValue(wm.getHttpBaseUrl()))
            .projectId(Property.ofValue(projectId))
            .wait(Property.ofValue(false))
            .build();

        Run.Output output = task.run(runContext(task));

        assertThat(output.getRunId(), is(runId));
        assertThat(output.getStatus(), is("RUNNING"));

        // Only the initial status snapshot is fetched, not a polling loop.
        verify(exactly(1), getRequestedFor(urlEqualTo("/projects/" + projectId + "/runs/" + runId)));
    }

    @Test
    void reattachesToAnInFlightRunInsteadOfStartingADuplicate(WireMockRuntimeInfo wm) throws Exception {
        String projectId = "proj-" + IdUtils.create();
        String runId = "run-" + IdUtils.create();

        stubFor(
            post(urlEqualTo("/projects/" + projectId + "/runs"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(startBody(projectId, runId)))
        );
        stubFor(
            get(urlEqualTo("/projects/" + projectId + "/runs/" + runId))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(statusBody(projectId, runId, "RUNNING", null, null)))
        );

        Run task = Run.builder()
            .id(IdUtils.create())
            .type(Run.class.getName())
            .apiToken(Property.ofValue("dummy-token"))
            .baseUrl(Property.ofValue(wm.getHttpBaseUrl()))
            .projectId(Property.ofValue(projectId))
            .wait(Property.ofValue(false))
            .build();

        // Same RunContext reused across two run() calls simulates the same task run being retried
        // after a worker restart: the run ID persisted on the first call must be reused on the second.
        RunContext sharedRunContext = runContext(task);

        Run.Output first = task.run(sharedRunContext);
        Run.Output second = task.run(sharedRunContext);

        assertThat(first.getRunId(), is(runId));
        assertThat(second.getRunId(), is(runId));

        verify(exactly(1), postRequestedFor(urlEqualTo("/projects/" + projectId + "/runs")));
        verify(exactly(2), getRequestedFor(urlEqualTo("/projects/" + projectId + "/runs/" + runId)));
    }

    @Test
    void cancelsTheRunOnKill(WireMockRuntimeInfo wm) throws Exception {
        String projectId = "proj-" + IdUtils.create();
        String runId = "run-" + IdUtils.create();

        stubFor(
            post(urlEqualTo("/projects/" + projectId + "/runs"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(startBody(projectId, runId)))
        );
        stubFor(
            get(urlEqualTo("/projects/" + projectId + "/runs/" + runId))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(statusBody(projectId, runId, "RUNNING", null, null)))
        );
        stubFor(
            delete(urlEqualTo("/projects/" + projectId + "/runs/" + runId))
                .willReturn(aResponse().withStatus(200))
        );

        Run task = Run.builder()
            .id(IdUtils.create())
            .type(Run.class.getName())
            .apiToken(Property.ofValue("dummy-token"))
            .baseUrl(Property.ofValue(wm.getHttpBaseUrl()))
            .projectId(Property.ofValue(projectId))
            .wait(Property.ofValue(false))
            .build();

        task.run(runContext(task));
        task.kill();

        verify(exactly(1), deleteRequestedFor(urlEqualTo("/projects/" + projectId + "/runs/" + runId)));
    }

    @Test
    void failsClearlyWhenProjectIdIsMissing(WireMockRuntimeInfo wm) {
        Run task = Run.builder()
            .id(IdUtils.create())
            .type(Run.class.getName())
            .apiToken(Property.ofValue("dummy-token"))
            .baseUrl(Property.ofValue(wm.getHttpBaseUrl()))
            .build();

        // The @NotNull constraint on projectId is enforced by RunContext itself during rendering, before
        // Run's own orElseThrow() ever runs, so a missing value surfaces as a ConstraintViolationException.
        ConstraintViolationException thrown = assertThrows(ConstraintViolationException.class, () -> task.run(runContext(task)));
        assertThat(thrown.getMessage(), containsString("projectId"));
    }

    @Test
    void sendsInputParamsInTheStartRequestBody(WireMockRuntimeInfo wm) throws Exception {
        String projectId = "proj-" + IdUtils.create();
        String runId = "run-" + IdUtils.create();

        stubFor(
            post(urlEqualTo("/projects/" + projectId + "/runs"))
                .withRequestBody(equalToJson("{\"inputParams\": {\"run_date\": \"2026-01-01\"}}"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(startBody(projectId, runId)))
        );
        stubFor(
            get(urlEqualTo("/projects/" + projectId + "/runs/" + runId))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(statusBody(projectId, runId, "RUNNING", null, null)))
        );

        Run task = Run.builder()
            .id(IdUtils.create())
            .type(Run.class.getName())
            .apiToken(Property.ofValue("dummy-token"))
            .baseUrl(Property.ofValue(wm.getHttpBaseUrl()))
            .projectId(Property.ofValue(projectId))
            .inputParams(Property.ofValue(Map.of("run_date", "2026-01-01")))
            .wait(Property.ofValue(false))
            .build();

        task.run(runContext(task));

        verify(exactly(1), postRequestedFor(urlEqualTo("/projects/" + projectId + "/runs"))
            .withRequestBody(equalToJson("{\"inputParams\": {\"run_date\": \"2026-01-01\"}}")));
    }

    @Test
    void failsClearlyWhenPollFrequencyIsNotPositive(WireMockRuntimeInfo wm) {
        Run task = Run.builder()
            .id(IdUtils.create())
            .type(Run.class.getName())
            .apiToken(Property.ofValue("dummy-token"))
            .baseUrl(Property.ofValue(wm.getHttpBaseUrl()))
            .projectId(Property.ofValue("proj-" + IdUtils.create()))
            .pollFrequency(Property.ofValue(Duration.ZERO))
            .build();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> task.run(runContext(task)));
        assertThat(thrown.getMessage(), containsString("pollFrequency"));
    }

    @Test
    void throwsTimeoutNamingLastObservedStatus(WireMockRuntimeInfo wm) throws Exception {
        String projectId = "proj-" + IdUtils.create();
        String runId = "run-" + IdUtils.create();

        stubFor(
            post(urlEqualTo("/projects/" + projectId + "/runs"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(startBody(projectId, runId)))
        );
        stubFor(
            get(urlEqualTo("/projects/" + projectId + "/runs/" + runId))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(statusBody(projectId, runId, "RUNNING", null, null)))
        );

        Run task = Run.builder()
            .id(IdUtils.create())
            .type(Run.class.getName())
            .apiToken(Property.ofValue("dummy-token"))
            .baseUrl(Property.ofValue(wm.getHttpBaseUrl()))
            .projectId(Property.ofValue(projectId))
            .pollFrequency(Property.ofValue(Duration.ofMillis(200)))
            .maxDuration(Property.ofValue(Duration.ofSeconds(1)))
            .build();

        TimeoutException thrown = assertThrows(TimeoutException.class, () -> task.run(runContext(task)));
        assertThat(thrown.getMessage(), containsString(runId));
        assertThat(thrown.getMessage(), containsString("RUNNING"));
    }

    private RunContext runContext(Run task) {
        return TestsUtils.mockRunContext(this.runContextFactory, task, java.util.Map.of());
    }

    private static String startBody(String projectId, String runId) {
        return """
            {
              "projectId": "%s",
              "runId": "%s",
              "runUrl": "https://app.hex.tech/hex/%s/run/%s",
              "runStatusUrl": "https://app.hex.tech/api/v1/projects/%s/runs/%s",
              "traceId": "trace-1",
              "projectVersion": "3"
            }
            """.formatted(projectId, runId, projectId, runId, projectId, runId);
    }

    private static String statusBody(String projectId, String runId, String status, String startTime, String endTime) {
        return """
            {
              "projectId": "%s",
              "runId": "%s",
              "runUrl": "https://app.hex.tech/hex/%s/run/%s",
              "status": "%s",
              "projectVersion": "3",
              "startTime": %s,
              "endTime": %s,
              "traceId": "trace-1"
            }
            """.formatted(projectId, runId, projectId, runId, status, jsonValue(startTime), jsonValue(endTime));
    }

    private static String jsonValue(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }
}
