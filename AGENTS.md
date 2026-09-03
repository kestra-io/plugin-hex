# Kestra Hex Plugin

## What

- Provides plugin components under `io.kestra.plugin.hex.projects`.
- Includes `Run` (a `RunnableTask` that starts a Hex project run and, by default, waits for completion) and `Trigger` (a polling trigger that fires when a Hex project's most recent run completes).

## Why

- What user problem does this solve? Hex (hex.tech) ships no Java SDK, so running a Hex project from an orchestrator otherwise means hand-rolling HTTP calls against its REST API.
- Why would a team adopt this plugin in a workflow? It lets a Kestra flow start a Hex project run and wait for it inline, or react whenever a Hex project's run reaches a terminal state, whether that run was started from Kestra or elsewhere (manually, or on a schedule configured in Hex).
- What operational/business outcome does it enable? Hex dashboard/app refreshes become an orchestrated, observable step in a larger pipeline instead of a disconnected manual job.

## How

### Architecture

Single-module plugin. Source packages under `io.kestra.plugin`:

- `hex.projects`

Hex exposes a REST API only; there is no official Java SDK. All HTTP calls go through Kestra's built-in `io.kestra.core.http.client.HttpClient`, never a third-party dependency.

### Key Plugin Classes

- `io.kestra.plugin.hex.projects.Run`: `RunnableTask<Run.Output>`. Starts the latest published version of a project (`POST /projects/{projectId}/runs`) and, when `wait` is true (default), polls `GET /projects/{projectId}/runs/{runId}` until a terminal status. Reattaches to an already in-flight run instead of starting a duplicate (see Reattach below), and cancels the run via `DELETE` on `kill()`. With `assets.enableAuto`, emits one asset for the project (see Assets below).
- `io.kestra.plugin.hex.projects.Trigger`: `AbstractTrigger` + `PollingTriggerInterface` + `TriggerOutput<Run.Output>`. Polls `GET /projects/{projectId}/runs?limit=1&statusFilter=COMPLETED` and fires once the most recent run reaches `COMPLETED`, deduplicating on run ID.
- `io.kestra.plugin.hex.projects.HexConnectionInterface`: shared Hex API calls (start, get, list, cancel a run) as default methods, since a `Task` and a `Trigger` cannot share a common superclass. Implementors only expose `getApiToken()` and `getBaseUrl()`. Also holds the nested `HexRunList` record for the list-runs response and translates non-2xx responses into actionable messages.
- `io.kestra.plugin.hex.projects.RunStatus`: enum of Hex run statuses (`PENDING`/`RUNNING`/`ERRORED`/`COMPLETED`/`KILLED`/`UNABLE_TO_ALLOCATE_KERNEL`/`UNKNOWN`), with `isTerminal()`/`isFailure()` classification used by both `Run` and `Trigger`.
- `io.kestra.plugin.hex.projects.HexRun`: record for a single Hex run, used for both the start response and the status/list response, and mapped into `Run.Output` by both `Run` and `Trigger`.

### Reattach contract (Run)

`RunContext.stateStore()` is deprecated for removal as of Kestra 1.1.0, so `Run` does not use it. Instead it persists the Hex run ID directly to the flow's namespace KV store (`runContext.namespaceKv(runContext.flowInfo().namespace())`), keyed by `runContext.taskRunInfo().taskRunId()` (stable across attempts of the same task run, unique per execution, so concurrent executions of the same flow never collide). On `run()`, it looks up that key before deciding whether to call the start endpoint; if a run ID is already present, it reattaches to that run's status instead of starting a duplicate. The entry is refreshed on every attempt and cleared once the run reaches a terminal state. Its TTL is `maxDuration` plus a fixed 7-day buffer, deliberately not `maxDuration` alone: the entry has to outlive one full attempt (which can last up to `maxDuration`) plus the gap to the next retry, and refreshing it each attempt carries it across the whole retry sequence regardless of retry interval or attempt count. An entry that has expired is treated as no run in flight, so a fresh run is started.

### Assets (Run)

`Run` emits one asset for the Hex project when the flow sets `assets.enableAuto`, which is off by default, so Hex becomes the terminal node of a Fivetran to dbt to Hex chain. The type is the string `io.kestra.plugin.ee.assets.Dataset`, named as a string because EE owns the concrete types and this stays an OSS-only build. The asset id is the project id lowercased, since core constrains an id to `^[a-z0-9][a-z0-9._-]*`. Hex reports no project URL, so `location` is the run URL cut at `/run/`, and no upstream tables, so inputs stay user-declared through core's `assets.inputs`. Emission never fails the task: on OSS `emit()` throws `UnsupportedOperationException` and is logged at debug.

### Project Structure

```
plugin-hex/
├── src/main/java/io/kestra/plugin/hex/projects/
├── src/test/java/io/kestra/plugin/hex/projects/
├── build.gradle
└── README.md
```

## Local rules

- Base the wording on the implemented packages and classes, not on template README text.

## References

- https://kestra.io/docs/plugin-developer-guide
- https://kestra.io/docs/plugin-developer-guide/contribution-guidelines
- https://learn.hex.tech/docs/api/api-reference
