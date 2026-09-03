# How to use the Hex plugin

Run [Hex](https://hex.tech) notebook/app projects from Kestra flows, and react when a Hex run completes. Hex ships no Java SDK, so this plugin talks to the [Hex REST API](https://learn.hex.tech/docs/api/api-reference) through Kestra's own HTTP client.

## Authentication

Every task and trigger requires `apiToken` (required), a Hex API key created from your Hex workspace's API key settings. Store it as a [secret](https://kestra.io/docs/concepts/secret), for example `{{ secret('HEX_API_TOKEN') }}`, and never hardcode it in a flow. `baseUrl` (optional, defaults to `https://app.hex.tech/api/v1`) only needs to be overridden for a self-hosted Hex region. Both properties can be shared across flows with [plugin defaults](https://kestra.io/docs/workflow-components/plugin-defaults).

## Tasks

`projects.Run` starts the **latest published version** of a Hex project (`projectId`, required) and, by default (`wait: true`), polls until the run reaches a terminal state (`pollFrequency` default 5 seconds, `maxDuration` default 1 hour). Set `wait: false` to start the run and return immediately with its current status. Optional `inputParams` sets values for the project's already-declared input cells.

A run ending in `ERRORED`, `KILLED`, or `UNABLE_TO_ALLOCATE_KERNEL` fails the task with a message naming the run and its status; `COMPLETED` succeeds. Output exposes `runId`, `runUrl`, `status`, `projectVersion`, `startTime`, `endTime`, `elapsedTime`, and `traceId`.

If the task is killed, or the worker restarts mid-poll, `projects.Run` never starts a duplicate run: the run ID is persisted to the flow's namespace KV store keyed by the task run, and looked up again on every attempt before deciding whether to call the start endpoint. On a hard kill it also calls Hex's cancel endpoint for the in-flight run.

## Assets

With `assets.enableAuto` set on `projects.Run`, the task emits one asset for the Hex project it ran, so Hex becomes the terminal node of a lineage chain such as Fivetran to dbt to Hex. It is off by default, and lineage is an Enterprise Edition feature: on the open-source edition the emission is skipped and logged at debug.

The asset id is `projectId` verbatim, the type is `io.kestra.plugin.ee.assets.Dataset`, and the metadata carries `system: hex` plus `location`, the project's page in Hex. Hex's API reports no project name, so the graph labels the node with the project id.

Hex also does not report which tables a project reads, so upstream edges are declared in the flow with `assets.inputs`, using the same `database.schema.table` ids that plugin-dbt and plugin-fivetran emit:

```yaml
assets:
  enableAuto: true
  inputs:
    - id: analytics.marts.fct_orders
      type: io.kestra.plugin.ee.assets.Table
```

Only `projects.Run` emits. A project run on a Hex schedule and picked up by `projects.Trigger` produces no asset, so a chain relying on Hex-side scheduling still ends at its upstream tables. Nothing on the lineage path can fail a task whose run succeeded, and a failed run emits nothing, because Kestra does not record assets for a failed task run.

## Triggers

`projects.Trigger` polls a Hex project's most recent run (`projectId`, required) at a fixed `interval` (default 1 minute) and fires once that run reaches `COMPLETED`. It deduplicates on the run ID so the same completed run never fires twice, even across scheduler restarts. Useful when a Hex project runs outside Kestra (manually, or on a schedule configured in Hex) and a flow should react once it finishes; to run a project from Kestra itself and wait inline, use `projects.Run` instead. Output is the same shape as `projects.Run`'s: `{{ trigger.runId }}`, `{{ trigger.runUrl }}`, `{{ trigger.status }}`, and so on.
