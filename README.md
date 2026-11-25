# Camel Tack

![Camel Tack](src/main/resources/META-INF/resources/camel-tack-logo.png)

Camel Tack is a Camel-native runtime for mission-bound LLM agents.

Application authors express agent behavior as ordinary Camel routes. Tack adds a small producer component for agent primitives, catalogs for personas and routines, Kafka-backed inference lanes, optional MCP tools, profile-gated OpenShell execution, and observable Mission and AgentRun state.

The public authoring contract is the `tack:` component. Kafka routes provide durable scheduling, bounded model concurrency, partition-key ordering, request/reply correlation, and worker isolation. OpenShell provides a persistent execution boundary for tools that need a real working environment. These are framework-owned runtime capabilities, not application routes that adopters should customize casually.

> **Project status:** Tack is an evolving local/runtime prototype. Mission state and LLM reply waiters are currently in memory, and synchronous request/reply assumes one active orchestrator process. See [Current boundaries](#current-boundaries).

## Why Tack

Tack is designed around five ideas:

- **Routes are the workflow language.** A routine is a Camel route, not an opaque agent loop.
- **The component is the authoring boundary.** Routine authors invoke semantic operations such as `tack:act`, `tack:decide`, and `tack:create`.
- **Kafka is the model concurrency boundary.** Inference lanes queue work and enforce configured model-call concurrency.
- **Agent state is runtime state.** Missions, AgentRuns, thoughts, decisions, created values, and projection history live independently from any one model invocation.
- **Execution environments are explicit capabilities.** Tool profiles can expose MCP tools and, when allowed, persistent OpenShell sandbox execution.

## Architecture

Tack has two deliberately different surfaces.

### Application surface

Application code owns:

- personas;
- routines and subroutines;
- prompts and JSON schemas;
- inference-lane declarations;
- MCP server declarations and tool profiles;
- domain-specific route logic.

A routine looks like normal Camel DSL:

```java
from("direct:my-domain-coordinator")
        .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                Mission plan, known evidence, blockers, and next move.
                """))
        .to("tack:thought-enter?name=coordinator&maxTokens=1200")
        .setHeader(TackHeaders.PROMPT, constant("""
                Inspect the available project evidence and identify the next useful action.
                """))
        .to("tack:act?id=review-workspace&toolProfile=workspace-readonly")
        .setHeader(TackHeaders.PROMPT, constant("""
                Is this routine complete for the current pass?
                """))
        .to("tack:decide?id=done")
        .to("tack:thought-exit?name=coordinator");
```

### Runtime surface

Tack owns:

- mission admission routes;
- AgentRun scheduling and execution routes;
- Kafka request and reply routes for every inference lane;
- model-worker routes and LLM correlation;
- primitive execution and thought management;
- MCP client construction and tool-result limiting;
- profile-gated OpenShell sandbox execution and lifecycle;
- workspace projection updates.

These routes exist in the application `CamelContext`, so they remain inspectable with normal Camel tooling. They are synthetic runtime routes owned by Tack.

### End-to-end flow

```text
POST /missions
  -> admit Mission and AgentRuns
  -> publish AgentRunScheduled to Kafka

AgentRunScheduled consumer
  -> transition accepted AgentRun to running
  -> launch its routine asynchronously

application routine route
  -> tack:thought-enter
  -> tack:act / tack:decide / tack:create
  -> tack:thought-exit

Tack primitive
  -> prepare an LLM job
  -> publish to selected lane request topic

lane worker
  -> invoke configured model
  -> expose selected MCP tools and optional sandbox_exec
  -> publish LlmJobResult

reply consumer
  -> correlate by jobId
  -> resume waiting primitive
  -> update AgentRun state and projection history
```

See [docs/architecture.md](docs/architecture.md) for scheduling, lane, sandbox, and tool-pass details.

## Core model

### Mission

A `Mission` is the top-level goal and context container. It owns the mission ID, goal, status, metadata, and AgentRuns admitted for that mission.

### AgentRun

An `AgentRun` is one execution of one persona on one routine or declared subroutine. It carries persona and routine identity, structured input, an optional sandbox name, primitive results, lifecycle status, and parent lineage.

Parent/child lineage does not imply synchronous waiting. Scheduling a child AgentRun returns an accepted handle; the parent continues unless its own routine later observes that state.

### Persona

A `Persona` defines the actor presented to the model: name, description, traits, display metadata, and external identities. A persona does not directly own tools.

### Routine

A `RoutineDefinition` maps a catalog ID to a Camel route URI. It may declare one main route and named public subroutines and selects a default inference lane.

### ToolProfile

A `ToolProfile` defines the capabilities available during an act. Profiles can:

- attach MCP server definitions;
- allow or deny MCP tools;
- allow framework-provided tools such as `sandbox_exec`;
- add default tool arguments;
- provide prompt guidance;
- cap tool-result tokens.

When `tack:act` omits a profile, Tack uses the built-in no-tools profile.

### InferenceLane

An `InferenceLaneDefinition` is a Kafka-backed model work pool. It declares request/reply topics, endpoint/model configuration, worker parallelism, and partition-key strategy.

### Thought stack

Each AgentRun owns scoped `ThoughtFrame` values. `thought-enter` pushes a child scope, `act` rewrites the active leaf from new evidence, and `thought-exit` consolidates a populated child into its parent before popping it. Projection history preserves prior rewrites and exits.

## OpenShell persona sandboxes

Tack remains outside the sandbox. A persistent sandbox belongs to one **mission + persona** pair and is reused across that persona's AgentRuns for the mission. Different missions and personas receive different sandbox identities.

`sandbox_exec` is injected only when the selected `ToolProfile` explicitly allows `sandbox/sandbox_exec`. The bundled `source-control-write` profile uses it for repository contents, builds, tests, diffs, commits, and pushes under `/sandbox/repos`. Gitea remains the remote source-control surface for repository and PR metadata; the bundled Architect profile alone exposes repository provisioning, and final merge is isolated to the integration profile.

When the persona has a Gitea identity, Tack prepares an OpenShell provider and Git credentials for that sandbox. `POST /workspace/reset` discovers and deletes Tack-owned sandboxes as part of reset cleanup.

See [docs/openshell-sandboxes.md](docs/openshell-sandboxes.md) for lifecycle, network policy, configuration, and troubleshooting.

## Quick start

### Requirements

- JDK 21
- Kafka
- an OpenAI-compatible chat-completions endpoint, such as vLLM
- optional MCP servers for MCP-backed profiles
- optional OpenShell gateway for sandbox-backed profiles

Set `JAVA_HOME` to your JDK 21 installation or put its `bin` directory on your `PATH`. The Maven Wrapper (`./mvnw`, or `mvnw.cmd` on Windows) downloads Maven 3.9.11 automatically on first use and caches it for subsequent builds.

### Configure a local runtime

```bash
export KAFKA_BOOTSTRAP_SERVERS=localhost:9092
export LLM_MODEL_DEFAULT_OPENAI_BASE_URL=http://localhost:8000/v1
export LLM_MODEL_DEFAULT_OPENAI_MODEL=qwen-36-27b
export LLM_MODEL_DEFAULT_OPENAI_API_KEY=not-needed
export LLM_WORKER_ENABLED=true
export LLM_LOCAL_WORKER_ROUTE_VERIFICATION_ENABLED=true
export LLM_KAFKA_PROVISION_TOPICS=true
```

Enable OpenShell only when sandbox-backed tools are needed:

```bash
export AGENT_SANDBOX_OPENSHELL_ENABLED=true
export OPENSHELL_GATEWAY_ENDPOINT=http://openshell.tack.svc.cluster.local:8080
```

Start Quarkus:

```bash
./mvnw quarkus:dev
```

Submit a mission:

```bash
curl -s -X POST http://localhost:8080/missions \
  -H 'content-type: application/json' \
  --data '{
    "goal": "Build a small parking lot manager app.",
    "agentRuns": [{
      "personaId": "software-team/noah",
      "routineId": "software-team/director"
    }]
  }' | jq
```

Inspect runtime state:

```bash
curl -s http://localhost:8080/workspace/view | jq
```

Reset in-memory state and Tack-owned sandboxes:

```bash
curl -s -X POST http://localhost:8080/workspace/reset | jq
```

## Writing a Tack application

Catalog entries are discovered from CDI beans.

| Provider | Registers |
| --- | --- |
| `PersonaProvider` | Personas |
| `RoutineProvider` | Routine definitions and route URIs |
| `ToolProfileProvider` | Tool capability profiles |
| `InferenceLaneProvider` | Kafka/model inference lanes |

At minimum, an adopter supplies personas, routines, inference lanes, and the `AgentRunRequest` values submitted to a mission.

### Define a persona

```java
@ApplicationScoped
public class CoordinatorPersona implements PersonaProvider {
    public static final String ID = "my-domain/coordinator";

    @Override
    public List<Persona> personas() {
        return List.of(Persona.builder(ID)
                .name("Coordinator")
                .description("Coordinates mission work and starts useful AgentRuns.")
                .trait("Keeps plans concrete and reports blockers clearly.")
                .build());
    }
}
```

External identities can be attached with `identity(...)`. Use `agentRunStartAssignable(false)` when orchestration prompts should not propose that persona for new runs.

### Define a routine

```java
@ApplicationScoped
public class CoordinatorRoutine extends RouteBuilder implements RoutineProvider {
    public static final String ID = "my-domain/coordinator-routine";

    @Override
    public List<RoutineDefinition> routines() {
        return List.of(RoutineDefinition.builder(ID)
                .name("Coordinator Routine")
                .main("Gather context, choose the next work, coordinate it, and close out.",
                        "direct:my-domain-coordinator")
                .defaultLane("low-pass")
                .build());
    }

    @Override
    public void configure() {
        from("direct:my-domain-coordinator")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("Mission plan, evidence, blockers, and next move."))
                .to("tack:thought-enter?name=coordinator&maxTokens=1000")
                .setHeader(TackHeaders.PROMPT, constant("Inspect current workspace state and identify the next useful move."))
                .to("tack:act?id=workspace-review&toolProfile=workspace-readonly")
                .to("tack:thought-exit?name=coordinator");
    }
}
```

### Define an inference lane

Each provider declares one lane. `configName` selects its group under `llm.inference-lane`.
The framework resolves model and endpoint fallbacks along with sampling and tool-memory settings.

```java
@ApplicationScoped
public class LowPassLane implements InferenceLaneProvider {
    public static final String ID = "low-pass";
    private static final String CONFIG_NAME = "low";

    private final LaneConfiguration.Lane config;

    public LowPassLane(LaneConfiguration configuration) {
        this.config = configuration.lanes().get(CONFIG_NAME);
    }

    @Override
    public InferenceLaneDefinition inferenceLane() {
        return InferenceLaneDefinition.builder(ID)
                .configName(CONFIG_NAME)
                .requestTopic(config.requestTopic().orElse("llm.requests.low"))
                .responseTopic(config.replyTopic().orElse("llm.replies.low"))
                .parallelism(config.parallelism())
                .partitionKeyStrategy(config.partitionKeyStrategy().orElse(PartitionKeyStrategy.AGENT_RUN))
                .endpoint(config.endpoint().orElse(""))
                .model(config.model().orElse(""))
                .apiKeyConfigKey("llm.inference-lane.low.api-key")
                .apiKeyEnvVariable("LLM_LOW_API_KEY")
                .build();
    }
}
```

### Define an MCP server and tool profile

```java
@ApplicationScoped
public class SourceControlToolProfile implements ToolProfileProvider {
    @Override
    public List<ToolProfile> toolProfiles() {
        return List.of(ToolProfile.builder("source-control")
                .mcpServer(repository.definition())
                .allowTools("repository", "pull_request_read", "pull_request_write")
                .maxResultTokens(6000)
                .build());
    }
}
```

A server without an allow list exposes its tools except those explicitly denied. Cross-system profiles should generally use explicit allow lists. Framework-provided tools follow the same profile boundary; for example, `.allowTool("sandbox", "sandbox_exec")` opts a profile into sandbox execution.

## The `tack:` component

All primitive endpoints are synchronous from the calling route's perspective: the endpoint returns only after its semantic operation completes or fails.

| Header | Purpose |
| --- | --- |
| `TackHeaders.PROMPT` (`TackPrompt`) | Prompt consumed by `act`, `decide`, and `create` |
| `TackHeaders.THOUGHT_PURPOSE` (`TackThoughtPurpose`) | Purpose consumed by `thought-enter` |

### `tack:act`

Use `act` when the model may need tools and the active thought should be rewritten from resulting evidence.

```java
.setHeader(TackHeaders.PROMPT, constant("Confirm the target repository exists and report any blocker."))
.to("tack:act?id=source-readiness&toolProfile=source-control")
```

`act` performs a tool/action pass with the selected profile, then a schema-shaped synthesis pass that rewrites the active leaf thought.

Tool passes are bounded. The defaults are 50,000 tool-memory tokens, compaction at 50%, three recent tool interactions retained around compaction, and a semantic budget of 50 individual tool calls. Tack also detects repeated completed tool cycles. These are safety rails; routine prompts should still make absence, blockers, and sufficient evidence valid stop conditions.

### `tack:decide`

Use `decide` for a narrow boolean control-flow decision.

```java
.setHeader(TackHeaders.PROMPT, constant("Is this routine complete or blocked for the current pass?"))
.to("tack:decide?id=done")
```

The structured result is stored under `decisions.<decisionId>` and `decision.<decisionId>.result`.

### `tack:create`

Use `create` for schema-shaped JSON generation. Tack validates generated content against the selected schema and stores it under the create ID.

### Thoughts

`thought-enter` pushes a named frame with an approximate token budget. `thought-exit` pops the root or an empty child directly; a populated child is consolidated into its parent first. Failed consolidation leaves frames intact so route-level error handling can retry or fail safely.

## Scheduling AgentRuns

Mission admission and child scheduling use `AgentRunRequest`. Framework entry points accept one request or a list:

```java
.to("direct:start-agent-run")
.to("direct:start-agent-runs")
```

Scheduling validates the full batch, checks mission capacity, and prepares required sandboxes. It
commits all accepted runs before publishing `AgentRunScheduled` events from their handles. The
caller gets accepted handle data without waiting for routine completion.

The Kafka consumer restores authoritative state, transitions eligible runs to `running`, launches routine execution asynchronously with `wireTap`, and returns so Kafka can commit. Ordering a list of requests controls event publication order, not a guaranteed execution head start between accepted AgentRuns.

## Kafka inference lanes

For each lane Tack creates a Kafka worker path with `consumersCount=lane.parallelism` and `maxPollRecords=1`. `parallelism(1)` serializes model calls. Higher values create a partitioned work pool.

With parallelism greater than one, ordering is preserved only for records sharing a partition key. The default `AGENT_RUN` strategy keeps one AgentRun's jobs on the same partition while unrelated runs can use others.

Reply correlation is currently in memory by `jobId`, so one active orchestrator process must own the waiting request and its reply consumer.

## Configuration

### Kafka and runtime

| Environment variable | Default |
| --- | --- |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` |
| `AGENT_RUN_SCHEDULED_TOPIC` | `agent-runs.scheduled` |
| `AGENT_RUN_SCHEDULED_CONSUMER_GROUP` | `agent-run-scheduler` |
| `AGENT_RUN_SCHEDULED_CONSUMER_ENABLED` | `true` |
| `LLM_TOPIC_DEAD_LETTER` | `llm.dead-letter` |
| `LLM_WORKER_ENABLED` | `true` |
| `LLM_LOCAL_WORKER_ROUTE_VERIFICATION_ENABLED` | `true` |
| `LLM_REPLY_TIMEOUT_MS` | `900000` |

### Global model and tool-pass defaults

| Environment variable | Default |
| --- | --- |
| `LLM_MODEL_DEFAULT_OPENAI_BASE_URL` | `http://localhost:8000/v1` |
| `LLM_MODEL_DEFAULT_OPENAI_API_KEY` | `not-needed` |
| `LLM_MODEL_DEFAULT_OPENAI_MODEL` | `qwen-36-27b` |
| `LLM_MODEL_DEFAULT_OPENAI_TEMPERATURE` | `0` |
| `LLM_MODEL_DEFAULT_OPENAI_MAX_TOKENS` | `4092` |
| `LLM_MODEL_DEFAULT_OPENAI_TIMEOUT_MS` | `300000` |
| `LLM_MODEL_DEFAULT_OPENAI_STRICT_JSON_SCHEMA` | `true` |
| `LLM_MODEL_DEFAULT_OPENAI_THINKING_OFF_ENABLED` | `true` |
| `LLM_MODEL_DEFAULT_TOOL_MEMORY_MAX_TOKENS` | `50000` |
| `LLM_MODEL_DEFAULT_TOOL_MEMORY_COMPACTION_THRESHOLD` | `0.50` |
| `LLM_MODEL_DEFAULT_TOOL_MEMORY_RECENT_TOOL_INTERACTIONS` | `3` |
| `LLM_MODEL_DEFAULT_MAX_TOOL_CALLING_ROUND_TRIPS` | `50` |

### OpenShell runtime

| Environment variable | Default |
| --- | --- |
| `AGENT_SANDBOX_OPENSHELL_ENABLED` | `false` |
| `OPENSHELL_EXECUTABLE` | `openshell` |
| `OPENSHELL_GATEWAY_ENDPOINT` | `http://openshell.tack.svc.cluster.local:8080` |
| `OPENSHELL_CONTROL_TIMEOUT_SECONDS` | `180` |
| `OPENSHELL_EXEC_TIMEOUT_SECONDS` | `120` |
| `GITEA_URL` | empty |

### MCP runtime

| Environment variable | Default |
| --- | --- |
| `AGENT_MCP_TRANSPORT_TIMEOUT_MS` | `60000` |
| `AGENT_MCP_INITIALIZATION_TIMEOUT_MS` | `30000` |
| `AGENT_MCP_MAX_TOOL_RESULT_TOKENS` | `1000` |
| `AGENT_MCP_LOG_TRANSPORT_REQUESTS` | `false` |
| `AGENT_MCP_LOG_TRANSPORT_RESPONSES` | `false` |

## Bundled software-team example

The `us.nusun.tack.example` package is a demonstration catalog, not part of Tack's generic contract.

| Persona | Routine | Responsibility |
| --- | --- | --- |
| Noah | `software-team/director` | Maintains mission coverage and mission/product viability |
| Ethan | `software-team/product-owner` | Owns `epic` product outcomes through evidence-based acceptance |
| Owen | `software-team/project-manager` | Owns delivery flow, `story` topology, dependencies, WIP, and handoffs |
| Avery | `software-team/architect` | Owns repository architecture, integration, conflicts, and architecture-heavy engineering |
| Miles | `software-team/application-developer` | Owns assigned component `story` work and prioritized defect repairs |
| Quinn | `software-team/quality-engineer` | Owns test engineering, system behavior evidence, defect recording, and retest closure |

### Delivery model

Plane labels plus parent/child hierarchy carry the durable product/delivery model. Tack does not depend on native Plane work-item types for this example. Every classified work item carries exactly one attached classification label from this closed set. These labels are unrelated to Plane's native work-item type/type_id:

- **`epic`:** PO-owned product lifecycle object. PO defines, prioritizes, activates, and accepts it.
- **`story`:** PM-planned engineering delivery beneath an Epic. DEV or ARCH owns assigned lifecycle through Done.
- **`test`:** QE-owned test/evidence work beneath an Epic.
- **`defect`:** QE-raised product-failure claim. QE creates it unparented in Backlog with evidence; PO validates or rejects it, records whether it blocks Epic acceptance, and assigns product priority; PM routes accepted Todo Defects; DEV/ARCH repairs; QE retests and closes.

Plane state carries lifecycle, assignment carries ownership, parent/sub-work-item hierarchy carries decomposition, and relations carry blocking/order. Plane native `type_id` is not used for Tack classification. Classification is never inferred from title, wording, state, hierarchy, or role.

The team normally keeps roughly 2-3 Epics In Progress with a deeper Todo runway when downstream capacity supports it. Todo is the preparation boundary: PM may pre-decompose Backlog Stories, and once repository architecture is ready Director staffs DEV and QE without waiting for the Epic to become In Progress. PM targets roughly 2-3 active engineering units plus 2-3 executable Todo Stories per delivery role when independent work exists; QE uses the same practical 2-3 active plus 2-3 queued target for Tests. Story dependencies must remain acyclic. PM owns cancellation of obsolete Stories; QE owns Test promotion, clarified-retest resets, and cancellation of obsolete Tests. These are flow targets, not hard ceilings.

PM bootstrap establishes the Plane project and shared team channel. Team onboarding is a first-class recurring PM responsibility. On every PM orientation, onboarding status is observed directly by comparing the authoritative active roster with current Plane project membership and current shared Mattermost channel membership. Any missing active identity makes onboarding the highest-priority PM focus. PM does not rely on prior memory to establish parity and does not evict members based on roster absence. Architect provisions and baselines source control after that foundation exists. Once the system repository exists, PM reads its documentation and maintains `docs/project-basics.md` as compact canonical navigation linking the mission, Plane project, shared channel, repository map, role responsibilities, and stable delivery conventions.
 DEV and QE are staffed once a PO-owned Epic is Todo or In Progress and repository architecture is ready. QE test-code PRs use the same Architect integration path as implementation PRs, and a Test stays In Progress until required test code is merged. The repository model is deliberately simple:

```text
system-repo/
├── README.md
├── gitops/
├── docs/
└── tests/
    └── system/

component-repo(s)/
└── independently deployable application components
    ├── <anchor>-service   # runtime service, including API implementations
    └── <anchor>-api       # reserved for API specification/contract repositories
```

The system repository is the single home for system GitOps, system documentation, architecture decisions, and cross-component system behavior tests. Separate docs or QE repositories are not part of the model. The example expects mission Git repositories under the `agent-workspace` organization.

### Feedback loops

- **PO ↔ QE:** PO defines acceptance intent and Defect acceptance impact; QE owns Test lifecycle, builds evidence, raises defects, and retests clarified or repaired behavior; PO accepts the Epic.
- **PO ↔ DIR:** evidence-backed viability or mission-interpretation questions flow to Director without transferring backlog ownership.
- **DEV ↔ QE:** QE exposes failed behavior/testability needs; DEV implements component fixes and useful test seams.
- **ARCH ↔ DEV:** Architect integrates component work, resolves conflicts, and handles cross-cutting/system implementation.
- **PM ↔ everyone:** PM maintains synchronization, dependencies, assignments, accepted Defect routing, and flow reconciliation.

### Tool ownership

- **Plane:** projects, label-classified work items, hierarchy, assignments, lifecycle, dependencies, priorities, blockers, and evidence comments.
- **Gitea:** repositories, branches, commits, pull requests, releases, and integration evidence.
- **OpenShell:** local repository contents, builds, tests, diffs, commits, conflict recovery, and pushes.
- **Mattermost:** the shared current-mission channel for ordinary questions, handoffs, blockers, decisions, and awareness; DMs are reserved for genuinely private exchanges.

Gitea issue tracking, labels, milestones, and time tracking are intentionally excluded so Plane remains the delivery source of truth.

### Example tool profiles

| Profile | Scope |
| --- | --- |
| `workspace-readonly` | Read-only orientation across Plane, Gitea, and Mattermost |
| `workspace-coordination` | PM-only project/channel bootstrap and access alignment |
| `product-ownership` | PO Epic lifecycle/acceptance plus validation and prioritization of `defect` findings |
| `delivery-management` | PM Story planning, dependency/assignment flow, and routing of accepted Todo Defects |
| `quality-management` | QE `test` lifecycle, `defect` creation/retest evidence, and Epic evidence |
| `engineering-work` | DEV/ARCH execution updates for already-assigned `story` or `defect` work |
| `source-control` | Read-only Gitea source discovery and evidence |
| `source-control-write` | DEV source writes in Architect-established component repositories |
| `quality-source-control-write` | QA source writes in the Architect-established system repository |
| `project-documentation` | PM documentation writes to `docs/project-basics.md` in the system repository |
| `architect-source-control-write` | ARCH source writes plus repository provisioning/conflict recovery |
| `integration` | ARCH final pull-request integration authority |
| `team-chat` | Mattermost conversation and notifications |

Routine authors should choose the narrowest profile that supports one coherent act. Ownership rules belong in tool surfaces and durable work semantics, not repeated ad hoc prompt conventions.

## Workspace API

| Endpoint | Method | Purpose |
| --- | --- | --- |
| `/missions` | `POST` | Admit and schedule a mission |
| `/workspace/view` | `GET` | Inspect catalogs, missions, AgentRuns, thoughts, and timelines |
| `/workspace/reset` | `POST` | Reset in-memory workspace state and destroy Tack-owned sandboxes |

## Current boundaries

Tack is presently optimized for a local or single-orchestrator deployment.

- Mission, AgentRun, and projection state are in memory.
- LLM reply waiters are in memory and owned by one orchestrator process.
- Reply topics are single-partition in the current topology.
- Horizontally scaled orchestrators require reply-to routing or durable reply storage.
- AgentRun admission and scheduled-event publication are not transactional.
- AgentRun execution has no global hard limit; inference lanes control model concurrency instead.
- Kafka lane parallelism does not imply global FIFO ordering.
- MCP clients depend on the active persona identity and selected profile.
- OpenShell is optional and disabled by default; sandbox-backed tools require a reachable gateway and a permitting profile.

## Project layout

| Path | Purpose |
| --- | --- |
| `framework.persona` | Persona declarations, external accounts, catalog and safe views |
| `framework.routine` | Routine declarations and catalog, `tack:` component, prompts, thought scopes and primitive results |
| `framework.run` | Mission and run admission, scheduling, state, roster and projections |
| `framework.inference` | LLM jobs, queue routes, replies, model execution and schemas |
| `framework.inference.lane` | Lane declarations, registry, configuration and effective settings |
| `framework.inference.memory` | Transcript compaction, tool-loop guards and token counting |
| `framework.tool` | Tool profiles, safe views and MCP server declarations |
| `framework.tool.mcp` | MCP connections, tool discovery and dispatch, argument policy and result limits |
| `framework.sandbox` | OpenShell readiness, lifecycle, policy and execution |
| `framework.workspace` | HTTP endpoints, workspace views, topic provisioning and reset |
| `framework.logging` | Shared logging helpers |
| `src/main/java/us/nusun/tack/example` | Bundled software-team example |
| `docs/architecture.md` | Additional architecture notes |
| `docs/openshell-sandboxes.md` | OpenShell execution model and troubleshooting |

Framework packages are under `src/main/java/us/nusun/tack`. Tests mirror the capability packages under
`src/test/java` and use test-owned consumers and configuration.

## Development

Run tests:

```bash
./mvnw test
```

Run the full verification lifecycle:

```bash
./mvnw verify
```

Framework tests use synthetic personas, routines, tool profiles, routes, and test-owned configuration. Keep example providers and application settings out of test setup. Live services belong in explicit environment validation.

JaCoCo reports handwritten framework code in `target/site/jacoco`, including sandbox clients, manager, and tool. The report excludes the example and generated protobuf and gRPC code.

## Troubleshooting

### A mission is accepted but never starts

Check the scheduler consumer, Kafka connectivity, `agent-runs.scheduled`, and whether the run remains `accepted` in `/workspace/view`. Admission and publication are not yet transactional.

### An LLM request times out

Check the selected lane's worker logs, reply topic, reply-consumer group, model endpoint timeout, and `LLM_REPLY_TIMEOUT_MS`.

### MCP tools do not appear

Confirm the selected profile includes the server, the server URL is present, the persona has the required identity, the tool is allowed, and MCP initialization completed.

### `sandbox_exec` or sandbox work fails

See [OpenShell persona sandboxes](docs/openshell-sandboxes.md#troubleshooting) for sandbox creation, profile exposure, Git provider/authentication, package egress, execution timeouts, and reset/orphan cleanup.

### Tool results are truncated

The selected profile's `maxResultTokens` takes precedence. Otherwise Tack uses `AGENT_MCP_MAX_TOOL_RESULT_TOKENS`.

### Jobs from one AgentRun execute out of order

Use `PartitionKeyStrategy.AGENT_RUN`, verify request-topic partitioning, and remember that ordering applies only within one Kafka partition. Different lanes are independent queues.
