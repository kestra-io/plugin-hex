package io.kestra.plugin.hex.projects;

import java.time.Duration;
import java.util.Optional;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.AbstractTrigger;
import io.kestra.core.models.triggers.PollingTriggerInterface;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.models.triggers.TriggerOutput;
import io.kestra.core.models.triggers.TriggerService;
import io.kestra.core.storages.kv.KVMetadata;
import io.kestra.core.storages.kv.KVValueAndMetadata;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Trigger a flow when a Hex project run completes",
    description = """
        Polls a Hex project's most recent run at a fixed interval and fires when it reaches the `COMPLETED` status. \
        Useful when a Hex project is run outside Kestra (manually, or on a schedule configured in Hex) and a Kestra \
        flow should react once that run finishes. To run a Hex project from Kestra itself and wait for it inline, \
        use `io.kestra.plugin.hex.projects.Run` instead.

        Deduplicates on the run ID so the same completed run never fires the trigger twice.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Trigger a flow when the most recent run of a Hex project completes.",
            full = true,
            code = """
                id: on_hex_run_complete
                namespace: company.team

                triggers:
                  - id: dashboard_refreshed
                    type: io.kestra.plugin.hex.projects.Trigger
                    apiToken: "{{ secret('HEX_API_TOKEN') }}"
                    projectId: "00000000-0000-0000-0000-000000000000"
                    interval: PT1M

                tasks:
                  - id: notify
                    type: io.kestra.plugin.core.log.Log
                    message: "Hex run {{ trigger.runId }} completed: {{ trigger.runUrl }}"
                """
        )
    }
)
public class Trigger extends AbstractTrigger implements PollingTriggerInterface, TriggerOutput<Run.Output>, HexConnectionInterface {
    private static final String WATERMARK_KV_PREFIX = "hex_trigger_last_completed_run_";
    // Long enough to prevent a re-fire across scheduler restarts, short enough to expire on its own.
    private static final Duration WATERMARK_TTL = Duration.ofDays(30);

    @Schema(title = "Hex project ID", description = "The ID of the Hex project to monitor.")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> projectId;

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
        title = "Poll interval",
        description = "How often to check the project's most recent run. Default is 1 minute. The Hex API allows roughly 30 requests per minute, so avoid setting this below a few seconds."
    )
    @PluginProperty(group = "execution")
    @Builder.Default
    private final Duration interval = Duration.ofMinutes(1);

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {
        var runContext = conditionContext.getRunContext();
        var logger = runContext.logger();

        var rProjectId = runContext.render(this.projectId).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("projectId must be set"));

        var kv = runContext.namespaceKv(context.getNamespace());
        var kvKey = kvKey(context.getFlowId(), context.getTriggerId());

        String lastFiredRunId = kv.getValue(kvKey).map(value -> (String) value.value()).orElse(null);

        // Filter to COMPLETED server-side so a newer in-flight run cannot mask a just-completed one.
        Optional<HexRun> latestRun;
        try {
            latestRun = this.latestCompletedRun(runContext, rProjectId);
        } catch (Exception e) {
            logger.warn("Could not list Hex runs for project '{}': {}", rProjectId, e.getMessage());
            return Optional.empty();
        }

        if (latestRun.isEmpty()) {
            logger.debug("No completed runs found yet for Hex project '{}'", rProjectId);
            return Optional.empty();
        }

        var latest = latestRun.get();
        // Defensive: statusFilter should already guarantee this.
        if (latest.status() != RunStatus.COMPLETED) {
            logger.debug("Latest Hex run '{}' for project '{}' is not completed, status={}", latest.runId(), rProjectId, latest.status());
            return Optional.empty();
        }

        if (latest.runId().equals(lastFiredRunId)) {
            logger.debug("Hex run '{}' for project '{}' already fired, skipping", latest.runId(), rProjectId);
            return Optional.empty();
        }

        kv.put(kvKey, new KVValueAndMetadata(new KVMetadata(null, WATERMARK_TTL), latest.runId()));

        logger.info("Hex run '{}' for project '{}' completed, firing trigger", latest.runId(), rProjectId);
        var output = Run.Output.of(latest.runId(), latest);
        return Optional.of(TriggerService.generateExecution(this, conditionContext, context, output));
    }

    // Length-prefixes flowId so ("ab","c") and ("a","bc") cannot collide on one KV key.
    private static String kvKey(String flowId, String triggerId) {
        return WATERMARK_KV_PREFIX + flowId.length() + "_" + flowId + "_" + triggerId;
    }
}
