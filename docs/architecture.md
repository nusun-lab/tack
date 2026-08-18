# Architecture Notes

## Problem

A local LLM is a scarce resource. Agent teams create bursty demand: planners, builders, reviewers, fixers, and summarizers all want the model at once. If every agent calls vLLM directly, the system loses control over concurrency, observability, and failure handling.

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

## Inference Lane Parallelism

An inference lane is a shared Kafka-backed model work pool. Its `parallelism` controls how many jobs that lane can work on concurrently in the current single-orchestrator-instance topology.

`parallelism = 1` keeps the lane serialized. `parallelism > 1` creates a partitioned work pool: the worker route uses `consumersCount=parallelism` with `maxPollRecords=1`.

When topic provisioning is enabled, missing lane request topics are created with partitions equal to lane parallelism. Provisioning does not silently increase partitions for existing topics; existing request topics must already have at least `lane.parallelism()` partitions or startup fails clearly. Reply topics remain single-partition in the current single-orchestrator topology.

Lane-wide FIFO is not guaranteed when `parallelism > 1`. Ordering is preserved only for jobs with the same Kafka partition key. The default partition key strategy is `AGENT_RUN`, with fallback `agentRunId -> missionId -> jobId`. `MISSION` uses `missionId -> agentRunId -> jobId`, and `JOB` uses `jobId`.

Replies remain simple in this mode: reply topics are single-partition, reply records are keyed by `jobId`, and `InFlightReplyManager` completes the local in-memory waiter by `jobId`.

The current request/reply implementation assumes one active orchestrator instance owns those in-memory waiters. Lane worker parallelism is supported inside that topology. Horizontally scaled orchestrator/API instances require reply-to routing or durable reply storage and are intentionally out of scope for this mode.


## Software-Team Tool Boundaries

The bundled software-team example is deliberately outside framework core, but it documents one concrete way to keep MCP tool authority understandable.

Plane owns projects, planned work, assignments, status, dependencies, priorities, blockers, and delivery planning. Gitea owns repositories, files, branches, commits, pull requests, builds, tags, and releases. Mattermost owns conversation, questions, handoffs, and concise notifications.

The example publishes five profiles:

- `workspace-readonly`: read-only cross-system orientation with explicit allow lists for Plane, Gitea, and Mattermost.
- `workspace-coordination`: bounded cross-system coordination with explicit allow lists; it can update Plane, create/discover Gitea repositories, and communicate in Mattermost, but it cannot mutate source files, branches, pull requests, releases, or Gitea issue state.
- `project-management`: broad Plane profile with denials for destructive operations, intake, work logs, pages, feature administration, and custom-schema administration.
- `source-control`: broad Gitea profile with denials for destructive operations, native wiki surfaces, issue/label/milestone/time-tracking coordination, and unrelated admin surfaces.
- `team-chat`: broad Mattermost profile with denials for destructive operations, low-value UI state, reactions, pins, and bookmarks.

Gitea issues, labels, milestones, and time tracking are excluded because Plane is the single source of truth for planned work and status. Cross-system profiles use allow lists because combining systems increases blast radius. Domain profiles use deny lists because a single authoritative system can expose a broad surface without creating cross-system ambiguity.

The example declares Plane, Gitea, and Mattermost connection definitions as separate `McpServer` classes. Each `ToolProfile` class injects the server declarations it needs, composes those immutable definitions directly, and owns its capability policy; there is no inheritance between profiles.

Routine authors should choose the narrowest profile that supports the act. Composite profiles should avoid splitting one coherent act merely to access multiple systems, not become universal all-tools profiles.

## Tack act completion

Each `tack:act` submits one normalized `LlmJob` and performs one LangChain4j agent invocation. Camel adds the selected persona/profile MCP clients and the tagged, framework-owned `finish_act` AI tool to that invocation. The agent may complete zero or more ordinary tool round trips before calling `finish_act(summary, content)`. The terminal tool is immediate-return, so its result completes the same invocation without another model call. Tack uses `summary` for the step result and replaces the active durable thought with `content`; tool-loop messages remain ephemeral.

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

### Persistence

The in-memory agent-run state and LLM reply waiters are intentionally disposable in this proof. Replace them with durable storage when you care about restarts. A future startup reconciler can use durable run state to republish `AgentRunScheduled` for stale accepted or running runs.

### Result contracts

Eventually each request should specify a response contract:

- JSON schema
- maximum tokens
- retry policy
- timeout
- cacheability

The worker should reject invalid outputs instead of letting bad artifacts flow downstream.
