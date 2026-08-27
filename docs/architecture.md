# Architecture Notes

## Problem

A local LLM is a scarce resource. Agent teams create bursty demand: planners, builders, reviewers, fixers, and summarizers all want the model at once. If every agent calls vLLM directly, the system loses control over concurrency, priority, observability, and failure handling.

## Hypothesis

Kafka plus Camel is a strong fit for LLM sharing because it separates submission from execution.

- Kafka absorbs bursts.
- Camel makes the orchestration explicit and inspectable.
- The LLM worker route becomes the lane parallelism gate.
- Results become events that other agents can consume.

## AgentRun Scheduling

AgentRun scheduling is event-driven. Admission is deliberately short:

```text
direct:start-agent-run
  -> validate AgentRunRequest, including optional subroutineId
  -> enforce any caller-supplied active-run capacity limit
  -> create/record AgentRun as accepted
  -> publish AgentRunScheduled
  -> return AgentRunHandle
```

`AgentRunScheduled` is a durable start signal on `agent-runs.scheduled`, shaped as:

```json
{
  "missionId": "mission-...",
  "agentRunId": "run-..."
}
```

The Kafka consumer unmarshals the event, performs an idempotent accepted/scheduled-to-running transition, launches `direct:execute-agent-run` asynchronously, and returns. It does not hold the Kafka message open for the full routine.

`direct:execute-agent-run` rebuilds mission and AgentRun context from state, resolves either the full routine route or the selected declared subroutine route, invokes that route, and records complete or failed projection state. Duplicate scheduled events for running, complete, failed, or otherwise ineligible runs are ignored.

There is no global hard-coded AgentRun concurrency limit. The framework exposes active-run counting and an optional admission capacity property so routines can enforce a mission-specific limit. Active runs are accepted, scheduled, and running; complete and failed runs are terminal for capacity. LLM/model concurrency remains controlled by inference lanes.

Lifecycle hardening is future work. AgentRun admission records accepted state and then publishes `AgentRunScheduled`. If publication fails after admission, a run can remain accepted without a scheduled event. That is acceptable for this local/in-memory prototype; a future reconciler should scan stale accepted runs and republish `AgentRunScheduled`.

## Persona execution environments

OpenShell provides an execution boundary for tools that need a real working environment. Tack itself remains outside the sandbox.

A sandbox is owned by one mission + persona pair. The sandbox name is deterministic for that pair and the same sandbox is reused across that persona's AgentRuns in the mission. Repository checkouts and other working state therefore survive routine boundaries while different missions and personas remain isolated.

`sandbox_exec` is not globally available. `LlmAgentFactory` injects it only when the selected `ToolProfile` explicitly allows logical server `sandbox` and tool `sandbox_exec`. The sandbox name is also written into AgentRun working state so later execution can reconnect to the same environment.

When a persona has Gitea credentials, the sandbox manager prepares an OpenShell provider and configures Git identity and credentials. Repository contents are then manipulated with ordinary Git, filesystem, build, and test commands inside `/sandbox/repos`. Gitea MCP remains the remote control surface for repository discovery/provisioning, branch and commit metadata, pull requests, releases, and remote verification.

Developer sandbox network policy explicitly allows Maven Central, the Red Hat Maven repository, npm, and PyPI package hosts. The npm endpoint permits encoded slashes so scoped package paths work.

Sandbox lifecycle is independent of AgentRun lifecycle. `POST /workspace/reset` asks the sandbox manager to discover and delete Tack-owned sandboxes so persistent execution state does not leak between experiments. See [OpenShell persona sandboxes](openshell-sandboxes.md).

## Tool-pass safety

Tool/action memory is ephemeral to one tool pass. Tack keeps a bounded recent interaction window and compacts tool memory once the configured threshold is crossed instead of carrying an indefinitely growing transcript.

The default controls are:

- 50,000 tool-memory tokens;
- compaction at 50% of that budget;
- three recent tool interactions preserved around compaction;
- a semantic budget of 50 individual tool calls per tool pass.

Tack also detects repeated completed tool executions, including repeated cycles up to length five, before executing the next repeated cycle. The semantic call budget is checked before another tool executes, so a runaway pass fails at the Tack boundary rather than silently continuing through an unbounded tool loop.

These mechanisms are safety rails, not the primary routine design. Act prompts should still define a valid stop when evidence is found, when required state is absent, or when an exact blocker is known.

## Inference Lane Parallelism

An inference lane is a shared Kafka-backed model work pool. Its `parallelism` controls how many jobs that lane can work on concurrently in the current single-orchestrator-instance topology.

`parallelism = 1` keeps the lane serialized. `parallelism > 1` creates a partitioned work pool: the worker route uses `consumersCount=parallelism` with `maxPollRecords=1`.

When topic provisioning is enabled, missing lane request topics are created with partitions equal to lane parallelism. Provisioning does not silently increase partitions for existing topics; existing request topics must already have at least `lane.parallelism()` partitions or startup fails clearly. Reply topics remain single-partition in the current single-orchestrator topology.

Lane-wide FIFO is not guaranteed when `parallelism > 1`. Ordering is preserved only for jobs with the same Kafka partition key. The default partition key strategy is `AGENT_RUN`, with fallback `agentRunId -> missionId -> jobId`. `MISSION` uses `missionId -> agentRunId -> jobId`, and `JOB` uses `jobId`.

Replies remain simple in this mode: reply topics are single-partition, reply records are keyed by `jobId`, and `InFlightReplyManager` completes the local in-memory waiter by `jobId`.

The current request/reply implementation assumes one active orchestrator instance owns those in-memory waiters. Lane worker parallelism is supported inside that topology. Horizontally scaled orchestrator/API instances require reply-to routing or durable reply storage and are intentionally out of scope for this mode.

## Software-Team Tool Boundaries

The bundled software-team example is deliberately outside framework core, but it documents one concrete way to keep tool authority understandable.

Plane owns projects, planned work, assignments, status, dependencies, priorities, blockers, and delivery planning. Gitea owns remote repository state and review metadata. Mattermost owns conversation, questions, handoffs, and concise notifications. OpenShell is the local execution boundary for repository content work.

The example publishes six profiles:

- `workspace-readonly`: read-only cross-system orientation with explicit allow lists for Plane, Gitea, and Mattermost.
- `workspace-coordination`: bounded cross-system coordination with explicit allow lists; it can update Plane, create/discover Gitea repositories, and communicate in Mattermost, but it cannot mutate source files, branches, pull requests, releases, or Gitea issue state.
- `project-management`: broad Plane profile with denials for destructive operations, intake, work logs, pages, feature administration, and custom-schema administration.
- `source-control`: broad read/remote Gitea profile with denials for destructive operations, native wiki surfaces, issue/label/milestone/time-tracking coordination, and unrelated admin surfaces.
- `source-control-write`: Gitea remote operations plus `sandbox_exec`; repository contents, builds, tests, diffs, commits, and pushes happen in the persistent persona sandbox rather than through Gitea file-content mutation APIs.
- `team-chat`: broad Mattermost profile with denials for destructive operations, low-value UI state, reactions, pins, and bookmarks.

Gitea issues, labels, milestones, and time tracking are excluded because Plane is the single source of truth for planned work and status. Cross-system profiles use allow lists because combining systems increases blast radius. Domain profiles use deny lists because a single authoritative system can expose a broad surface without creating cross-system ambiguity.

The example declares Plane, Gitea, and Mattermost connection definitions as separate `McpServer` classes. Each `ToolProfile` class injects the server declarations it needs, composes those immutable definitions directly, and owns its capability policy; there is no inheritance between profiles. OpenShell tool exposure follows the same profile boundary even though `sandbox_exec` is provided by Tack rather than MCP.

Routine authors should choose the narrowest profile that supports the act. Composite profiles should reduce redundant model passes when one coherent act needs multiple systems, not become universal all-tools profiles.

## Why not just use a Java BlockingQueue?

A local queue is fine for a toy. Kafka gives you the properties you will need once agents become separate services:

- durable queue
- replayable request/response log
- multi-producer fan-in
- worker isolation
- metrics
- future partitioning strategy
- OpenShift-friendly operational model

## Near-term decisions

### Priority

Kafka does not natively reorder records inside a partition by priority. Options:

1. Use separate topics: `llm.requests.high`, `llm.requests.normal`, `llm.requests.low`.
2. Use a scheduler service that reads submitted jobs from a DB and publishes to Kafka in priority order.
3. Use Kafka only for accepted execution jobs and keep priority in Postgres.

For the first proof, keep priority as metadata only.

### Persistence

The in-memory agent-run state and LLM reply waiters are intentionally disposable in this proof. Replace them with durable storage when you care about restarts. A future startup reconciler can use durable run state to republish `AgentRunScheduled` for stale accepted or running runs.

### Result contracts

Eventually each request should specify a response contract:

- JSON schema
- maximum tokens
- retry policy
- timeout
- cacheability
- requester/role

The worker should reject invalid outputs instead of letting bad artifacts flow downstream.
