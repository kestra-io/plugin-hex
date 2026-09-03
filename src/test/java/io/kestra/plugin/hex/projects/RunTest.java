package io.kestra.plugin.hex.projects;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.event.Level;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.stubbing.Scenario;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.assets.Asset;
import io.kestra.core.models.assets.AssetsDeclaration;
import io.kestra.core.models.executions.LogEntry;
import io.kestra.core.models.property.Property;
import io.kestra.core.queues.QueueFactoryInterface;
import io.kestra.core.queues.QueueInterface;
import io.kestra.core.runners.AssetEmit;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.utils.IdUtils;
import io.kestra.core.utils.TestsUtils;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.validation.ConstraintViolationException;
import reactor.core.publisher.Flux;

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
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
@WireMockTest
@Execution(ExecutionMode.SAME_THREAD)
class RunTest {

    @Inject
    private RunContextFactory runContextFactory;

    @Inject
    @Named(QueueFactoryInterface.WORKERTASKLOG_NAMED)
    private QueueInterface<LogEntry> logQueue;

    @Inject
    private TestAssetManagerFactory assetManagerFactory;

    // The factory is a singleton shared with every other test class, and one test flips its unsupported flag,
    // so it is reset on the way out as well as the way in.
    @BeforeEach
    @AfterEach
    void resetAssets() {
        assetManagerFactory.clear();
    }

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
        assertThat(output.getRunUrl(), is("https://app.hex.tech/hex/" + projectId + "/run/" + runId));
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
        assertThat(thrown.getMessage(), containsString("finished with status ERRORED in 5 seconds"));
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
            .assets(new AssetsDeclaration(true, List.of(), List.of()))
            .build();

        Run.Output output = task.run(runContext(task));

        assertThat(output.getRunId(), is(runId));
        assertThat(output.getRunUrl(), is("https://app.hex.tech/hex/" + projectId + "/run/" + runId));
        assertThat(output.getStatus(), is("RUNNING"));
        // A queued run is not a produced dataset, even though the task succeeded.
        assertThat(assetManagerFactory.emitted(), is(empty()));

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
    void persistsReattachEntryWithATtlIndependentOfMaxDuration(WireMockRuntimeInfo wm) throws Exception {
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

        // A short maxDuration must NOT shorten the reattach entry's TTL: the retry that needs it happens
        // after maxDuration elapses, so the entry has to outlive it (this was the bug in issue #4).
        Run task = Run.builder()
            .id(IdUtils.create())
            .type(Run.class.getName())
            .apiToken(Property.ofValue("dummy-token"))
            .baseUrl(Property.ofValue(wm.getHttpBaseUrl()))
            .projectId(Property.ofValue(projectId))
            .wait(Property.ofValue(false))
            .pollFrequency(Property.ofValue(Duration.ofSeconds(1)))
            .maxDuration(Property.ofValue(Duration.ofSeconds(2)))
            .build();

        RunContext rc = runContext(task);
        task.run(rc);

        var entry = rc.namespaceKv(rc.flowInfo().namespace()).list().stream()
            .filter(e -> e.key().startsWith("hex_run_reattach_"))
            .findFirst()
            .orElseThrow();

        assertThat(entry.expirationDate().isAfter(Instant.now().plus(Duration.ofDays(1))), is(true));
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
    void failsClearlyWhenPollFrequencyExceedsMaxDuration(WireMockRuntimeInfo wm) {
        Run task = Run.builder()
            .id(IdUtils.create())
            .type(Run.class.getName())
            .apiToken(Property.ofValue("dummy-token"))
            .baseUrl(Property.ofValue(wm.getHttpBaseUrl()))
            .projectId(Property.ofValue("proj-" + IdUtils.create()))
            .pollFrequency(Property.ofValue(Duration.ofSeconds(30)))
            .maxDuration(Property.ofValue(Duration.ofSeconds(10)))
            .build();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> task.run(runContext(task)));
        assertThat(thrown.getMessage(), containsString("maxDuration"));
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

    @Test
    void logsACompletionLineWithTheFinalStatusAndDuration(WireMockRuntimeInfo wm) throws Exception {
        String projectId = "proj-" + IdUtils.create();
        String runId = "run-" + IdUtils.create();

        stubFor(
            post(urlEqualTo("/projects/" + projectId + "/runs"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(startBody(projectId, runId)))
        );
        stubFor(
            get(urlEqualTo("/projects/" + projectId + "/runs/" + runId))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                    .withBody(statusBody(projectId, runId, "COMPLETED", "2026-01-01T00:00:00Z", "2026-01-01T00:01:23Z")))
        );

        Run task = Run.builder()
            .id(IdUtils.create())
            .type(Run.class.getName())
            .apiToken(Property.ofValue("dummy-token"))
            .baseUrl(Property.ofValue(wm.getHttpBaseUrl()))
            .projectId(Property.ofValue(projectId))
            .build();

        List<LogEntry> logs = new CopyOnWriteArrayList<>();
        Flux<LogEntry> receive = TestsUtils.receive(logQueue, either -> logs.add(either.getLeft()));

        task.run(runContext(task));

        LogEntry completion = TestsUtils.awaitLog(logs, log -> log.getMessage() != null && log.getMessage().contains("finished with status"));
        receive.blockLast();

        assertThat(completion.getLevel(), is(Level.INFO));
        assertThat(completion.getMessage(), containsString("finished with status COMPLETED in 1 minute 23 seconds"));
        assertThat(completion.getMessage(), containsString("https://app.hex.tech/hex/" + projectId + "/run/" + runId));
    }

    @Test
    void formatsElapsedDurationForHumans() {
        assertThat(Run.humanDuration(Duration.ofSeconds(5)), is("5 seconds"));
        assertThat(Run.humanDuration(Duration.ofSeconds(83)), is("1 minute 23 seconds"));
        assertThat(Run.humanDuration(Duration.ofSeconds(3725)), is("1 hour 2 minutes 5 seconds"));
        assertThat(Run.humanDuration(Duration.ofHours(30)), is("1 day 6 hours"));
        // Sub-second and negative durations skip the formatter.
        assertThat(Run.humanDuration(Duration.ZERO), is("0ms"));
        assertThat(Run.humanDuration(Duration.ofMillis(850)), is("850ms"));
        assertThat(Run.humanDuration(Duration.ofMillis(-500)), is("-500ms"));
    }

    @Test
    void emitsTheProjectAssetWhenAssetsAreEnabled(WireMockRuntimeInfo wm) throws Exception {
        // Mixed case on purpose: core's Asset.id allows it, so the id must survive verbatim or a hand-declared
        // assets.inputs entry naming the same project would land on a second node.
        String projectId = "5A2B1C3D-1234-4A5B-8C9D-0E1F2A3B4C5D";
        String runId = "run-" + IdUtils.create();

        stubFor(
            post(urlEqualTo("/projects/" + projectId + "/runs"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(startBody(projectId, runId)))
        );
        stubFor(
            get(urlEqualTo("/projects/" + projectId + "/runs/" + runId))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                    .withBody(statusBody(projectId, runId, "COMPLETED", "2026-01-01T00:00:00Z", "2026-01-01T00:00:05Z")))
        );

        Run task = Run.builder()
            .id(IdUtils.create())
            .type(Run.class.getName())
            .apiToken(Property.ofValue("dummy-token"))
            .baseUrl(Property.ofValue(wm.getHttpBaseUrl()))
            .projectId(Property.ofValue(projectId))
            .assets(new AssetsDeclaration(true, List.of(), List.of()))
            .build();

        task.run(runContext(task));

        List<AssetEmit> emitted = assetManagerFactory.emitted();
        assertThat(emitted, hasSize(1));

        Asset asset = emitted.get(0).outputs().get(0);
        assertThat(asset.getId(), is(projectId));
        assertThat(asset.getType(), is("io.kestra.plugin.ee.assets.Dataset"));
        assertThat(asset.getMetadata().get("system"), is("hex"));
        // The project's own page, which Hex only reports as the prefix of a run URL.
        assertThat(asset.getMetadata().get("location"), is("https://app.hex.tech/hex/" + projectId));
    }

    @Test
    void emitsNoAssetWhenEnableAutoIsFalse(WireMockRuntimeInfo wm) throws Exception {
        String projectId = "proj-" + IdUtils.create();
        String runId = "run-" + IdUtils.create();

        stubFor(
            post(urlEqualTo("/projects/" + projectId + "/runs"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(startBody(projectId, runId)))
        );
        stubFor(
            get(urlEqualTo("/projects/" + projectId + "/runs/" + runId))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                    .withBody(statusBody(projectId, runId, "COMPLETED", "2026-01-01T00:00:00Z", "2026-01-01T00:00:05Z")))
        );

        Run task = Run.builder()
            .id(IdUtils.create())
            .type(Run.class.getName())
            .apiToken(Property.ofValue("dummy-token"))
            .baseUrl(Property.ofValue(wm.getHttpBaseUrl()))
            .projectId(Property.ofValue(projectId))
            // Declared but off, which is the case the task's own gate has to catch.
            .assets(new AssetsDeclaration(false, List.of(), List.of()))
            .build();

        task.run(runContext(task));

        assertThat(assetManagerFactory.emitted(), is(empty()));
    }

    @Test
    void completesWhenTheEditionCannotEmitAssets(WireMockRuntimeInfo wm) throws Exception {
        String projectId = "proj-" + IdUtils.create();
        String runId = "run-" + IdUtils.create();
        assetManagerFactory.unsupported(true);

        stubFor(
            post(urlEqualTo("/projects/" + projectId + "/runs"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(startBody(projectId, runId)))
        );
        stubFor(
            get(urlEqualTo("/projects/" + projectId + "/runs/" + runId))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                    .withBody(statusBody(projectId, runId, "COMPLETED", "2026-01-01T00:00:00Z", "2026-01-01T00:00:05Z")))
        );

        Run task = Run.builder()
            .id(IdUtils.create())
            .type(Run.class.getName())
            .apiToken(Property.ofValue("dummy-token"))
            .baseUrl(Property.ofValue(wm.getHttpBaseUrl()))
            .projectId(Property.ofValue(projectId))
            .assets(new AssetsDeclaration(true, List.of(), List.of()))
            .build();

        Run.Output output = task.run(runContext(task));

        assertThat(output.getStatus(), is("COMPLETED"));
    }

    private RunContext runContext(Run task) {
        return TestsUtils.mockRunContext(this.runContextFactory, task, Map.of());
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
