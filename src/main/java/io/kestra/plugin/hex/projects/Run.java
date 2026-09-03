package io.kestra.plugin.hex.projects;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.lang3.time.DurationFormatUtils;
import org.slf4j.Logger;

import io.kestra.core.exceptions.ResourceExpiredException;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.assets.AssetsDeclaration;
import io.kestra.core.models.assets.Custom;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.AssetEmit;
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
import static java.util.Objects.requireNonNullElse;

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

        With `assets.enableAuto` set, emits one asset for the Hex project so Hex appears as the terminal consumer of a \
        lineage chain. Hex's API does not report which tables a project reads, so upstream edges are declared with \
        `assets.inputs`.
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
                      run_date: "{{ now() | date('yyyy-MM-dd') }}"
                    wait: false
                """
        ),
        @Example(
            title = "Run a Hex project and record it as the terminal node of a dbt lineage chain.",
            full = true,
            code = """
                id: run_hex_project_with_assets
                namespace: company.team

                tasks:
                  - id: refresh_dashboard
                    type: io.kestra.plugin.hex.projects.Run
                    apiToken: "{{ secret('HEX_API_TOKEN') }}"
                    projectId: "00000000-0000-0000-0000-000000000000"
                    assets:
                      enableAuto: true
                      # Hex reports no upstream tables, so the project's sources are declared here using the
                      # database.schema.table ids that dbt and Fivetran emit, which is what joins the graph.
                      inputs:
                        - id: analytics.marts.fct_orders
                          type: io.kestra.plugin.ee.assets.Table
                        - id: analytics.marts.dim_customers
                          type: io.kestra.plugin.ee.assets.Table
                """
        )
    }
)
public class Run extends Task implements RunnableTask<Run.Output>, HexConnectionInterface {
    private static final String REATTACH_KV_PREFIX = "hex_run_reattach_";
    // EE owns the concrete asset types and has no report or dashboard type, so a Hex project is a Dataset, the
    // same type Fivetran uses for its connector-grain asset. Named as a string so this stays an OSS-only build.
    private static final String ASSET_TYPE = "io.kestra.plugin.ee.assets.Dataset";
    private static final String ASSET_SYSTEM = "hex";
    // Added on top of maxDuration to size the reattach entry's TTL. The entry is refreshed on every
    // attempt and deleted on a terminal state, so this is only a backstop: it has to outlive one attempt
    // (hence maxDuration) plus the gap to the next retry, and refreshing carries it across the whole
    // sequence regardless of retry interval or attempt count.
    private static final Duration REATTACH_TTL_BUFFER = Duration.ofDays(7);

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

    @Getter(AccessLevel.NONE)
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private final transient AtomicBoolean cancelled = new AtomicBoolean(false);

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
            logger.info("Started Hex run '{}' for project '{}'", runId, rProjectId);
        } else {
            logger.info("Reattaching to existing Hex run '{}' for project '{}' instead of starting a new one", runId, rProjectId);
        }
        // Refresh on every attempt so the entry survives the whole retry sequence, even when maxDuration
        // itself is longer than the buffer.
        storeRunId(runContext, runId, rMaxDuration.plus(REATTACH_TTL_BUFFER));

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

        var summary = summarize(runId, rProjectId, currentRun);
        if (currentRun.status().isFailure()) {
            // Kestra logs a task exception at ERROR, so this is the failed run's completion line.
            throw new IllegalStateException(summary);
        }

        logger.info(summary);

        emitAsset(runContext, rProjectId, currentRun);

        return Output.of(runId, currentRun);
    }

    @Override
    public void kill() {
        if (isKilled.compareAndSet(false, true)) {
            Optional.ofNullable(killable.get()).ifPresent(Runnable::run);
        }
    }

    private void cancelQuietly(RunContext runContext, String rProjectId, String runId, Logger logger) {
        if (!cancelled.compareAndSet(false, true)) {
            return;
        }
        try {
            this.cancelRun(runContext, rProjectId, runId);
            logger.info("Cancelled Hex run '{}' for project '{}'", runId, rProjectId);
        } catch (Exception e) {
            logger.warn("Could not cancel Hex run '{}' for project '{}': {}", runId, rProjectId, e.getMessage());
        }
    }

    // The KV key is the task run ID: stable across retries of the same task run, unique per execution.
    // An expired entry means the run is no longer tracked, so start fresh; a real store failure (IOException)
    // still propagates rather than silently starting a duplicate.
    private String existingRunId(RunContext runContext) throws IOException {
        try {
            return runContext.namespaceKv(runContext.flowInfo().namespace())
                .getValue(kvKey(runContext))
                .map(value -> (String) value.value())
                .orElse(null);
        } catch (ResourceExpiredException e) {
            return null;
        }
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

    // Records the project as a lineage node so a Fivetran -> dbt -> Hex chain does not stop at the dbt marts.
    // Called only after the run succeeded, because core skips the asset upsert for a failed task run.
    // The id is the project id verbatim: core's Asset.id allows mixed case, and rewriting it would leave a
    // hand-declared assets.inputs entry pointing at a second, disconnected node.
    // Nothing here can fail the task, since lineage is metadata about the run and never the run itself.
    private void emitAsset(RunContext runContext, String projectId, HexRun run) {
        try {
            AssetsDeclaration declaration = this.getAssets();
            if (declaration == null || !runContext.render(declaration.getEnableAuto()).as(Boolean.class).orElse(false)) {
                return;
            }

            // The asset is the project, so nothing here describes one run of it: a run's status and timing are
            // already task outputs, and would go stale on the asset the moment the next run started.
            var metadata = new LinkedHashMap<String, Object>();
            metadata.put("system", ASSET_SYSTEM);
            var location = projectUrl(run.runUrl());
            if (location != null) {
                metadata.put("location", location);
            }

            var asset = Custom.builder()
                .id(projectId)
                .type(ASSET_TYPE)
                .metadata(metadata)
                .build();

            runContext.assets().emit(new AssetEmit(List.of(), List.of(asset)));
        } catch (UnsupportedOperationException e) {
            runContext.logger().debug("Asset emission is not supported in this edition, skipping lineage.");
        } catch (Exception e) {
            runContext.logger().warn("Unable to emit the Hex asset for project '{}'.", projectId, e);
        }
    }

    // Hex reports a run URL but no project URL, and the workspace slug it starts with is not derivable, so the
    // project's page is everything before the last run segment. Null when Hex reported no URL to cut.
    private static String projectUrl(String runUrl) {
        if (runUrl == null) {
            return null;
        }

        int runSegment = runUrl.lastIndexOf("/run/");
        return runSegment > 0 ? runUrl.substring(0, runSegment) : null;
    }

    // Used for both the log line and the failure message, so a run reads the same either way.
    private static String summarize(String runId, String projectId, HexRun run) {
        var link = requireNonNullElse(run.runUrl(), "unavailable");

        if (run.status().isTerminal()) {
            return "Hex run '%s' for project '%s' finished with status %s in %s. View it in Hex: %s"
                .formatted(runId, projectId, run.status(), humanDuration(run.elapsedDuration()), link);
        }

        return "Hex run '%s' for project '%s' is %s, returning without waiting for completion. View it in Hex: %s"
            .formatted(runId, projectId, run.status(), link);
    }

    // Duration.toString() would log "PT2M3S". formatDurationWords throws below zero and rounds sub-second to "0s".
    static String humanDuration(Duration duration) {
        if (duration == null) {
            return "an unknown duration";
        }

        long millis = duration.toMillis();
        return millis < 1000 ? millis + "ms" : DurationFormatUtils.formatDurationWords(millis, true, true);
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
