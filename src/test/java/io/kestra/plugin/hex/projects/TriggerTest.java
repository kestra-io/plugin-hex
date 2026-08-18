package io.kestra.plugin.hex.projects;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.utils.IdUtils;
import io.kestra.core.utils.TestsUtils;

import jakarta.inject.Inject;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

@KestraTest
@WireMockTest
class TriggerTest {

    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void firesWhenTheMostRecentRunIsCompleted(WireMockRuntimeInfo wm) throws Exception {
        String projectId = "proj-" + IdUtils.create();
        String runId = "run-" + IdUtils.create();

        stubFor(
            get(urlEqualTo("/projects/" + projectId + "/runs?limit=1&statusFilter=COMPLETED"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(listBody(projectId, runId, "COMPLETED")))
        );

        Trigger trigger = defaultTrigger(wm, projectId);
        var context = TestsUtils.mockTrigger(runContextFactory, trigger);
        Optional<Execution> execution = trigger.evaluate(context.getKey(), context.getValue());

        assertThat(execution.isPresent(), is(true));
        assertThat(execution.get().getTrigger().getVariables().get("runId"), is(runId));
        assertThat(execution.get().getTrigger().getVariables().get("status"), is("COMPLETED"));
    }

    @Test
    void doesNotFireWhenNoRunHasCompletedYet(WireMockRuntimeInfo wm) throws Exception {
        String projectId = "proj-" + IdUtils.create();

        // With statusFilter=COMPLETED, a project whose latest run is still in flight returns no runs.
        stubFor(
            get(urlEqualTo("/projects/" + projectId + "/runs?limit=1&statusFilter=COMPLETED"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{\"runs\": []}"))
        );

        Trigger trigger = defaultTrigger(wm, projectId);
        var context = TestsUtils.mockTrigger(runContextFactory, trigger);
        Optional<Execution> execution = trigger.evaluate(context.getKey(), context.getValue());

        assertThat(execution.isPresent(), is(false));
    }

    @Test
    void doesNotFireTwiceOnTheSameCompletedRun(WireMockRuntimeInfo wm) throws Exception {
        String projectId = "proj-" + IdUtils.create();
        String runId = "run-" + IdUtils.create();

        stubFor(
            get(urlEqualTo("/projects/" + projectId + "/runs?limit=1&statusFilter=COMPLETED"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(listBody(projectId, runId, "COMPLETED")))
        );

        Trigger trigger = defaultTrigger(wm, projectId);
        var context = TestsUtils.mockTrigger(runContextFactory, trigger);

        Optional<Execution> first = trigger.evaluate(context.getKey(), context.getValue());
        Optional<Execution> second = trigger.evaluate(context.getKey(), context.getValue());

        assertThat(first.isPresent(), is(true));
        assertThat(second.isPresent(), is(false));

        verify(2, getRequestedFor(urlEqualTo("/projects/" + projectId + "/runs?limit=1&statusFilter=COMPLETED")));
    }

    @Test
    void doesNotFireWhenNoRunsExistYet(WireMockRuntimeInfo wm) throws Exception {
        String projectId = "proj-" + IdUtils.create();

        stubFor(
            get(urlEqualTo("/projects/" + projectId + "/runs?limit=1&statusFilter=COMPLETED"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{\"runs\": []}"))
        );

        Trigger trigger = defaultTrigger(wm, projectId);
        var context = TestsUtils.mockTrigger(runContextFactory, trigger);
        Optional<Execution> execution = trigger.evaluate(context.getKey(), context.getValue());

        assertThat(execution.isPresent(), is(false));
    }

    private Trigger defaultTrigger(WireMockRuntimeInfo wm, String projectId) {
        return Trigger.builder()
            .id(IdUtils.create())
            .type(Trigger.class.getName())
            .apiToken(Property.ofValue("dummy-token"))
            .baseUrl(Property.ofValue(wm.getHttpBaseUrl()))
            .projectId(Property.ofValue(projectId))
            .build();
    }

    private static String listBody(String projectId, String runId, String status) {
        return """
            {
              "runs": [
                {
                  "projectId": "%s",
                  "runId": "%s",
                  "runUrl": "https://app.hex.tech/hex/%s/run/%s",
                  "status": "%s",
                  "projectVersion": "3",
                  "traceId": "trace-1"
                }
              ]
            }
            """.formatted(projectId, runId, projectId, runId, status);
    }
}
