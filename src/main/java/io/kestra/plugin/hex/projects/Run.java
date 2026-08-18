package io.kestra.plugin.hex.projects;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;

import io.kestra.core.exceptions.ResourceExpiredException;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.kestra.core.storages.kv.KVMetadata;
import io.kestra.core.storages.kv.KVValueAndMetadata;
import io.kestra.core.utils.Await;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import static io.kestra.core.utils.Rethrow.throwSupplier;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Run a Hex project and wait for completion",
    description = """
        Starts the latest published version of a Hex project through the Hex API (https://learn.hex.tech/docs/api/api-reference) \
        and, by default, polls until the run reaches a terminal state. Set `wait` to false to start the run and return \
        immediately with its ID.

        If the task is retried after a worker restart, it reattaches to the run it already started instead of starting a \
        duplicate: the run ID is persisted to the flow's namespace KV store keyed by this task run, and is looked up again \
        on every attempt before deciding whether to call the start endpoint.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Run a Hex project and wait for it to complete.",
            full = true,
            code = """
                id: run_hex_project
                namespace: company.team

                tasks:
                  - id: run_dashboard_refresh
                    type: io.kestra.plugin.hex.projects.Run
                    apiToken: "{{ secret('HEX_API_TOKEN') }}"
                    projectId: "00000000-0000-0000-0000-000000000000"
                """
        ),
        @Example(
            title = "Start a Hex project run with input parameters, without waiting for it to finish.",
            full = true,
            code = """
                id: start_hex_project
                namespace: company.team

                tasks:
                  - id: start_ingestion
                    type: io.kestra.plugin.hex.projects.Run
                    apiToken: "{{ secret('HEX_API_TOKEN') }}"
                    projectId: "00000000-0000-0000-0000-000000000000"
                    inputParams:
                      run_date: "{{ trigger.date | date('yyyy-MM-dd') }}"
                    wait: false
                """
        )
    }
)
public class Run extends Task implements RunnableTask<Run.Output>, HexConnectionInterface {
    private static final String REATTACH_KV_PREFIX = "hex_run_reattach_";

    @Schema(title = "Hex project ID", description = "The ID of the Hex project to run.")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> projectId;

    @Schema(
        title = "Input parameters",
        description = "Values for the project's input cells, as a map of parameter name to value. Only parameters already declared as input cells in the Hex project can be set this way."
    )
    @PluginProperty(group = "main")
    private Property<Map<String, Object>> inputParams;

    @Schema(
        title = "Hex API token",
        description = "Bearer token for the Hex API. Generate one from your Hex workspace's API key settings and store it as a Kestra secret."
    )
    @NotNull
    @ToString.Exclude
    @PluginProperty(secret = true, group = "connection")
    private Property<String> apiToken;

    @Schema(
        title = "Hex API base URL",
        description = "Base endpoint for all requests. Defaults to `https://app.hex.tech/api/v1`; override only for a self-hosted Hex region."
    )
    @NotNull
    @Builder.Default
    @PluginProperty(group = "connection")
    private Property<String> baseUrl = Property.ofValue(DEFAULT_BASE_URL);

    @Schema(
        title = "Wait for completion",
        description = "When true (default), poll the run until it reaches a terminal state. When false, start the run (or reattach to one already in flight) and return immediately with its current status."
    )
    @Builder.Default
    @PluginProperty(group = "execution")
    private Property<Boolean> wait = Property.ofValue(true);

    @Schema(
        title = "Poll frequency",
        description = "Interval between run status checks while waiting for completion. Default is 5 seconds. Keep this conservative: the Hex API allows roughly 30 status requests per minute."
    )
    @Builder.Default
    @PluginProperty(group = "execution")
    private Property<Duration> pollFrequency = Property.ofValue(Duration.ofSeconds(5));

    @Schema(
        title = "Maximum wait duration",
        description = "Upper bound for waiting when `wait` is true, after which the task fails with a timeout. Default is 1 hour."
    )
    @Builder.Default
    @PluginProperty(group = "execution")
    private Property<Duration> maxDuration = Property.ofValue(Duration.ofHours(1));

    @Getter(AccessLevel.NONE)
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private final transient AtomicReference<Runnable> killable = new AtomicReference<>();

    @Getter(AccessLevel.NONE)
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private final transient AtomicBoolean isKilled = new AtomicBoolean(false);

    @Override
    public Run.Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();

        var rProjectId = runContext.render(this.projectId).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("projectId must be set"));
        var rWait = runContext.render(this.wait).as(Boolean.class).orElse(true);
        var rPollFrequency = runContext.render(this.pollFrequency).as(Duration.class).orElse(Duration.ofSeconds(5));
        if (rPollFrequency.isNegative() || rPollFrequency.isZero()) {
            throw new IllegalArgumentException("pollFrequency must be a positive duration, but was " + rPollFrequency);
        }
        var rMaxDuration = runContext.render(this.maxDuration).as(Duration.class).orElse(Duration.ofHours(1));
        if (rPollFrequency.compareTo(rMaxDuration) > 0) {
            throw new IllegalArgumentException(
                "pollFrequency (" + rPollFrequency + ") must not be greater than maxDuration (" + rMaxDuration + ")"
            );
        }
        var rInputParams = runContext.render(this.inputParams).asMap(String.class, Object.class);

        String runId = existingRunId(runContext);
        if (runId == null) {
            runId = this.startRun(runContext, rProjectId, rInputParams).runId();
            storeRunId(runContext, runId, rMaxDuration);
            logger.info("Started Hex run '{}' for project '{}'", runId, rProjectId);
        } else {
            logger.info("Reattaching to existing Hex run '{}' for project '{}' instead of starting a new one", runId, rProjectId);
        }

        String finalRunId = runId;
        killable.set(() -> cancelQuietly(runContext, rProjectId, finalRunId, logger));
        // kill() may have already flipped isKilled while killable was still null (race above); catch up now.
        if (isKilled.get()) {
            cancelQuietly(runContext, rProjectId, finalRunId, logger);
        }

        var currentRun = this.getRun(runContext, rProjectId, runId);
        AtomicReference<HexRun> lastSeen = new AtomicReference<>(currentRun);

        if (rWait && !currentRun.status().isTerminal()) {
            try {
                currentRun = Await.until(
                    throwSupplier(() -> {
                        var polled = this.getRun(runContext, rProjectId, finalRunId);
                        lastSeen.set(polled);
                        return polled.status().isTerminal() ? polled : null;
                    }),
                    rPollFrequency,
                    rMaxDuration
                );
            } catch (TimeoutException e) {
                throw new TimeoutException(
                    "Hex run '" + runId + "' did not complete within " + rMaxDuration
                        + ", last observed status was " + lastSeen.get().status()
                );
            }
        }

        if (currentRun.status().isTerminal()) {
            clearRunId(runContext);
        }

        if (currentRun.status().isFailure()) {
            throw new IllegalStateException(
                "Hex run '" + runId + "' for project '" + rProjectId + "' ended with status " + currentRun.status()
                    + "; see the run in Hex for details: " + currentRun.runUrl()
            );
        }

        return Output.of(runId, currentRun);
    }

    @Override
    public void kill() {
        if (isKilled.compareAndSet(false, true)) {
            Optional.ofNullable(killable.get()).ifPresent(Runnable::run);
        }
    }

    private void cancelQuietly(RunContext runContext, String rProjectId, String runId, Logger logger) {
        try {
            this.cancelRun(runContext, rProjectId, runId);
            logger.info("Cancelled Hex run '{}' for project '{}'", runId, rProjectId);
        } catch (Exception e) {
            logger.warn("Could not cancel Hex run '{}' for project '{}': {}", runId, rProjectId, e.getMessage());
        }
    }

    // The KV key is the task run ID: stable across retries of the same task run, unique per execution.
    private String existingRunId(RunContext runContext) throws IOException, ResourceExpiredException {
        return runContext.namespaceKv(runContext.flowInfo().namespace())
            .getValue(kvKey(runContext))
            .map(value -> (String) value.value())
            .orElse(null);
    }

    private void storeRunId(RunContext runContext, String runId, Duration ttl) throws IOException {
        runContext.namespaceKv(runContext.flowInfo().namespace())
            .put(kvKey(runContext), new KVValueAndMetadata(new KVMetadata(null, ttl), runId));
    }

    private void clearRunId(RunContext runContext) {
        try {
            runContext.namespaceKv(runContext.flowInfo().namespace()).delete(kvKey(runContext));
        } catch (Exception e) {
            runContext.logger().debug("Could not clear Hex run reattach state: {}", e.getMessage());
        }
    }

    private static String kvKey(RunContext runContext) {
        return REATTACH_KV_PREFIX + runContext.taskRunInfo().taskRunId();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Hex run ID", description = "Identifier of the Hex project run.")
        private final String runId;

        @Schema(title = "Run URL", description = "Link to view this run in the Hex UI.")
        private final String runUrl;

        @Schema(
            title = "Run status",
            description = "Status reported by Hex, e.g. `PENDING`, `RUNNING`, `COMPLETED`, `ERRORED`, `KILLED`, or `UNABLE_TO_ALLOCATE_KERNEL`. When `wait` is false, this is a snapshot taken right after the run was started or reattached, so it is typically not yet terminal."
        )
        private final String status;

        @Schema(title = "Project version", description = "Published version of the Hex project that was run.")
        private final String projectVersion;

        @Schema(title = "Start time", description = "When the run started executing.")
        private final Instant startTime;

        @Schema(title = "End time", description = "When the run reached a terminal state. Null while the run has not completed yet.")
        private final Instant endTime;

        @Schema(
            title = "Elapsed time",
            description = "Duration of the run, taken from Hex's reported elapsed time or, if absent, computed from `startTime`/`endTime`."
        )
        private final Duration elapsedTime;

        @Schema(title = "Trace ID", description = "Identifier Hex uses to correlate this run internally, useful when contacting Hex support.")
        private final String traceId;

        static Output of(String runId, HexRun run) {
            return Output.builder()
                .runId(runId)
                .runUrl(run.runUrl())
                .status(run.status() != null ? run.status().name() : null)
                .projectVersion(run.projectVersion())
                .startTime(run.startTime())
                .endTime(run.endTime())
                .elapsedTime(run.elapsedDuration())
                .traceId(run.traceId())
                .build();
        }
    }
}
