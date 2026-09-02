# How to use the Hex plugin

Run [Hex](https://hex.tech) notebook/app projects from Kestra flows, and react when a Hex run completes. Hex ships no Java SDK, so this plugin talks to the [Hex REST API](https://learn.hex.tech/docs/api/api-reference) through Kestra's own HTTP client.

## Authentication

Every task and trigger requires `apiToken` (required), a Hex API key created from your Hex workspace's API key settings. Store it as a [secret](https://kestra.io/docs/concepts/secret), for example `{{ secret('HEX_API_TOKEN') }}`, and never hardcode it in a flow. `baseUrl` (optional, defaults to `https://app.hex.tech/api/v1`) only needs to be overridden for a self-hosted Hex region. Set both properties on each task.

## Tasks

`projects.Run` starts the **latest published version** of a Hex project (`projectId`, required) and, by default (`wait: true`), polls until the run reaches a terminal state (`pollFrequency` default 5 seconds, `maxDuration` default 1 hour). Set `wait: false` to start the run and return immediately with its current status. Optional `inputParams` sets values for the project's already-declared input cells.

A run ending in `ERRORED`, `KILLED`, or `UNABLE_TO_ALLOCATE_KERNEL` fails the task with a message naming the run and its status; `COMPLETED` succeeds. Output exposes `runId`, `runUrl`, `status`, `projectVersion`, `startTime`, `endTime`, `elapsedTime`, and `traceId`.

If the task is killed, or the worker restarts mid-poll, `projects.Run` never starts a duplicate run: the run ID is persisted to the flow's namespace KV store keyed by the task run, and looked up again on every attempt before deciding whether to call the start endpoint. On a hard kill it also calls Hex's cancel endpoint for the in-flight run.

## Triggers

`projects.Trigger` polls a Hex project's most recent run (`projectId`, required) at a fixed `interval` (default 1 minute) and fires once that run reaches `COMPLETED`. It deduplicates on the run ID so the same completed run never fires twice, even across scheduler restarts. Useful when a Hex project runs outside Kestra (manually, or on a schedule configured in Hex) and a flow should react once it finishes; to run a project from Kestra itself and wait inline, use `projects.Run` instead. Output is the same shape as `projects.Run`'s: `{{ trigger.runId }}`, `{{ trigger.runUrl }}`, `{{ trigger.status }}`, and so on.
