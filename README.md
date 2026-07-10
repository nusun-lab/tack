# LLM Share Camel Kafka

A Quarkus and Apache Camel framework for running mission-bound LLM agents through structured workflows, Kafka-backed model execution, and optional MCP tools.

The framework treats an agent run as application state, not as a single long-lived LangChain4j object. Camel routes define the workflow, Kafka serializes model work, and the catalog describes the personas, routines, tool profiles, and inference lanes available to the system.

## Highlights

* **Mission-bound agent runs** with isolated parent/child state.
* **Catalog-driven configuration** for personas, routines, tool profiles, and inference lanes.
* **Kafka-backed LLM queueing** so model calls can be serialized and routed by lane.
* **Reusable Camel primitives** for action, decision, schema-shaped creation, and child agent startup.
* **Two-pass tool execution** for `direct:act`: one pass for tool use, one pass for structured `StepResult` synthesis.
* **Optional MCP integration** with persona-specific credentials, tool filtering, default arguments, and bounded tool results.
* **Workspace projection API** for inspecting active missions and agent runs during local development.

## Architecture

A mission moves through the framework in a small number of explicit steps:

1. `POST /missions` accepts a goal plus one or more explicit `AgentRunRequest` entries.
2. The mission route creates and records each requested `AgentRun` as accepted, publishes one durable `AgentRunScheduled` start signal per run, and returns accepted handles quickly.
3. The `AgentRunScheduled` Kafka consumer transitions accepted runs to running, launches routine execution asynchronously, and then finishes the Kafka route so the event can be committed.
4. Async routine execution rebuilds the run context, runs the configured routine route, and records complete or failed state in the workspace projection.
5. Routines call framework primitives such as `direct:act`, `direct:decide`, `direct:create`, and `direct:start-agent-run`.
6. `direct:start-agent-run` records child agent runs, publishes the same scheduled event, and returns accepted handles immediately.
7. LLM work is queued through Kafka by inference lane.
8. Replies return through Kafka and update the agent run projection.

During execution, a mission is a goal/context container. An `AgentRun` is one persona/routine execution scheduled inside that mission. Parent/child agent runs are lineage only; no agent run synchronously waits for another agent run to complete.

## Requirements

* Java 21
* Maven
* Kafka
* An OpenAI-compatible chat/completions endpoint, such as vLLM
* Optional MCP server definitions declared by tool profiles. The bundled example profiles include Mattermost, Gitea, and Plane.

The project currently uses Quarkus 3.36.0 and a snapshot Camel LangChain4j agent dependency because endpoint-level agent configuration and JSON schema support are still ahead of the released Camel Quarkus line used by the rest of the application.

## Quick start

Start Kafka and your OpenAI-compatible model endpoint, then configure the local run:

```bash
export KAFKA_BOOTSTRAP_SERVERS=localhost:9092

export LLM_MODEL_DEFAULT_OPENAI_BASE_URL=http://localhost:8000/v1
export LLM_MODEL_DEFAULT_OPENAI_MODEL=qwen-36-27b
export LLM_MODEL_DEFAULT_OPENAI_API_KEY=not-needed

export LLM_WORKER_ENABLED=true
export LLM_LOCAL_WORKER_ROUTE_VERIFICATION_ENABLED=true
export LLM_KAFKA_PROVISION_TOPICS=true
```

Run the application:

```bash
./mvnw quarkus:dev
```

Submit a mission:

```bash
curl -s -X POST http://localhost:8080/missions \
  -H 'content-type: application/json' \
  --data '{
    "goal": "Build a small parking lot manager app. The team should clarify scope, coordinate work, and start one implementation path.",
    "agentRuns": [
      { "personaId": "software-team/noah", "routineId": "software-team/director" }
    ]
  }' | jq
```

Example response:

```json
{
  "missionId": "mission-...",
  "status": "accepted",
  "agentRuns": [
    {
      "agentRunId": "run-...",
      "missionId": "mission-...",
      "parentAgentRunId": null,
      "personaId": "software-team/noah",
      "routineId": "software-team/director",
      "status": "accepted"
    }
  ]
}
```

Inspect the workspace:

```bash
curl -s http://localhost:8080/workspace/view | jq
```

Reset local workspace state between experiments:

```bash
curl -s -X POST http://localhost:8080/workspace/reset | jq
```

## Bundled example

The bundled `us.nusun.llmshare.example` package demonstrates a software-team catalog. It is an example namespace, not a framework requirement.

| Persona | Persona ID            | Routine ID                            | Purpose                                   |
| ------- | --------------------- | ------------------------------------- | ----------------------------------------- |
| Noah    | `software-team/noah`  | `software-team/director`              | Starts and coordinates the mission.       |
| Owen    | `software-team/owen`  | `software-team/project-manager`       | Coordinates work and project setup.       |
| Ethan   | `software-team/ethan` | `software-team/product-owner`         | Clarifies scope and acceptance criteria.  |
| Miles   | `software-team/miles` | `software-team/application-developer` | Implements and reports delivery progress. |

Mission requests explicitly choose which `AgentRunRequest` objects to schedule. To start the bundled example, submit Noah on the Director routine in the `agentRuns` array.

### Bundled Git organization

The bundled software-team example expects all shared Git work to live under the `agent-workspace` organization.

* The PM bootstrap routine records `agent-workspace` as a project basic in the canonical documentation repository.
* The shared documentation repository and target source repository should be created or reused under `agent-workspace`.
* Agents should not create project repositories in their personal Git namespaces.
* If an agent cannot access `agent-workspace`, it should record a setup blocker instead of creating a duplicate repository elsewhere.

### Software-team tool profiles

The bundled software-team example publishes exactly five tool profiles. Public profile ID constants live on the individual `*ToolProfile` classes, and routines reference those constants rather than duplicating literals.

| Profile | MCP servers | Policy | Intended use |
| ------- | ----------- | ------ | ------------ |
| `workspace-readonly` | Plane, Gitea, Mattermost | Explicit allow lists on every server | Small read-only orientation across planned work, implementation evidence, and conversation context. |
| `workspace-coordination` | Plane, Gitea, Mattermost | Explicit allow lists on every server | Bounded coordination: Plane updates, Gitea repository discovery/creation, and concise Mattermost handoffs. |
| `project-management` | Plane | Broad Plane access with deny list | Deep Plane planning, work item breakdown, assignments, dependencies, states, blockers, priorities, and delivery planning. |
| `source-control` | Gitea | Broad Gitea access with deny list | Repository, file, branch, commit, pull request, build, tag, and release work. |
| `team-chat` | Mattermost | Broad Mattermost access with deny list | Focused conversation, questions, handoffs, notifications, channel setup, and membership work. |

Ownership boundaries are strict:

* Plane owns projects, planned work, assignments, status, dependencies, priorities, blockers, and delivery planning.
* Gitea owns repositories, files, branches, commits, pull requests, builds, tags, and releases.
* Mattermost owns conversation, questions, handoffs, and concise notifications.

Gitea issue tracking, labels, milestones, and time tracking are intentionally unavailable. They would create a second project-management system and split ownership from Plane. Mattermost messages should link to authoritative Plane work items, Gitea artifacts, or canonical documentation pages rather than recreating durable state.

The cross-system profiles use explicit allow lists because a single act can otherwise receive too much authority across three systems. The three domain profiles use broad access with deny lists because their jobs are intentionally domain-specific and need room to use new non-conflicting tools added by those MCP servers.

Routine authors should choose the narrowest profile that fully supports the act. Use `workspace-readonly` for cross-system orientation, `workspace-coordination` for bounded Plane/Gitea/Mattermost coordination, a full domain profile for deep domain work, and `team-chat` for chat administration not included in coordination. Composite profiles should reduce redundant passes when one coherent operation needs multiple systems; they should not become universal all-tools profiles.

## Core concepts

### Mission

A mission is the top-level goal/context container. It owns the mission ID, goal text, status, and metadata. It schedules one or more agent runs.

### AgentRun

An `AgentRun` is one execution of one persona on one routine. It carries working state, current primitive state, thought-stack state, latest results, and projection data. Agent runs may schedule more agent runs. Parent/child means lineage, not blocking. Starting an agent run returns an accepted handle immediately; output is eventual projection/result state. Admission records accepted state, `AgentRunScheduled` consumption records running state, and the async routine records complete or failed state.

### Persona

A persona defines the actor: name, description, behavioral traits, display metadata, and external identity mappings. It does not directly own tools.

### Routine

A routine is a Camel route plus catalog metadata. Routines should compose framework primitives and domain-specific route logic. Framework core should stay generic.

### ToolProfile

A tool profile defines which MCP servers and tools are visible during `direct:act`. Profiles can declare MCP server connection settings, allow tools, deny tools, add default arguments, and cap returned tool-result size. If an act omits a profile, the framework uses an internal no-tools profile; adopters do not need to publish a `none` profile.

### InferenceLane

An inference lane defines where LLM jobs are queued and which model endpoint/model serves them. The bundled example provides `low-pass` and `high-pass` lanes.

## Framework primitives

### `direct:act`

Use `direct:act` when the model may need tools and the routine needs a structured `StepResult` afterward.

```java
.process(routine.act("source-readiness")
        .prompt("Confirm the target repository exists and report blockers.")
        .toolProfile(SourceToolProfile.SOURCE_CONTROL))
.to("direct:act")
```

`direct:act` performs a tool/action pass first, then synthesizes the result into the common `StepResult` schema. This keeps tool usage and structured route state separate.

### `direct:decide`

Use `direct:decide` for narrow control-flow questions.

```java
.process(routine.decide("done")
        .prompt("Is this routine complete or blocked for this pass?"))
.to("direct:decide")
.choice()
    .when(simple("${exchangeProperty[decision.done.result]} == true"))
        .stop()
.end()
```

The boolean result is exposed at:

* `decisions.<decisionId>`
* `decision.<decisionId>.result`

### `direct:create`

Use `direct:create` for schema-shaped JSON generation.

```java
.process(routine.create("mvp-slice")
        .schemaId("mvp-slice-schema")
        .schemaJson("""
                {
                  "type": "object",
                  "properties": {
                    "component": { "type": "string" }
                  },
                  "required": ["component"]
                }
                """)
        .prompt("Create a small MVP slice description."))
.to("direct:create")
```

`direct:create` is generic. It does not automatically inject catalog data. If a prompt needs catalog context, compose the prompt explicitly with Camel Simple expressions:

```java
.process(routine.create("planned-agent-runs")
        .schema("classpath:schemas/example/software-team/agent-run-requests-schema.json")
        .prompt(simple("""
                Given this mission, propose software-team AgentRunRequest objects to schedule.

                Mission:
                ${exchangeProperty.mission.goal}

                Personas:
                ${bean:personaCatalog.agentRunStartAssignableJson}

                Routines:
                ${bean:routineCatalog.startableJson}

                Return only schema-compliant JSON.
                """)))
.to("direct:create")
```

### `direct:start-agent-run`

Use `direct:start-agent-run` to schedule another agent run from the same `AgentRunRequest` shape used by mission requests.

```json
{
  "personaId": "software-team/owen",
  "routineId": "software-team/project-manager",
  "input": {
    "repo": "parking-lot-manager"
  }
}
```

The framework supplies mission id, agent run id, parent lineage, status, timestamps, and lifecycle projection data. The route records the child run, publishes `AgentRunScheduled`, returns an accepted `AgentRunHandle` immediately, and does not wait for the scheduled routine to complete. The `input` object is preserved as structured working state for the scheduled run.

The scheduled event is intentionally small:

```json
{
  "missionId": "mission-...",
  "agentRunId": "run-..."
}
```

The default topic is `agent-runs.scheduled`. The event is a durable start signal, not the routine execution payload.

## Defining catalog entries

Catalog entries are discovered from CDI beans. The main provider interfaces are:

| Provider                   | Registers                 |
| -------------------------- | ------------------------- |
| `PersonaProvider`          | Personas                  |
| `RoutineProvider`          | Routine definitions       |
| `ToolProfileProvider`      | MCP tool profiles         |
| `InferenceLaneProvider`    | LLM queue/model lanes     |

## Create your own catalog

At minimum, a framework adopter provides:

* `PersonaProvider` for the actors available to missions.
* `RoutineProvider` for Camel routes those actors can run.
* `InferenceLaneProvider` for queue topics, endpoint overrides, and optional lane model names.
* `AgentRunRequest` entries in each `POST /missions` request for the AgentRuns the framework should schedule.

Optional pieces:

* `ToolProfileProvider` when routines need MCP tools. Tool profiles can include `McpServerDefinition` entries for each server they expose, including concrete URL, transport, auth header format, extra headers, default tool arguments, deny lists, and result limits.

### Persona example

```java
@ApplicationScoped
public class CoordinatorPersona implements PersonaProvider {
    @ConfigProperty(name = "persona.coordinator.identity.gitea.token", defaultValue = "")
    String giteaToken = "";

    @Override
    public List<Persona> personas() {
        return List.of(Persona.builder("my-domain/coordinator")
                .name("Coordinator")
                .description("Coordinates mission work and starts useful child AgentRuns.")
                .trait("Keeps plans concrete and reports blockers clearly.")
                .label("COORD")
                .color("38;2;100;180;255")
                .identity("gitea", giteaToken, "coordinator-agent")
                .build());
    }
}
```

Use `agentRunStartAssignable(false)` when a persona should not be proposed by orchestration prompts that start child runs.

### Routine example

```java
@ApplicationScoped
public class CoordinatorRoutine extends RouteBuilder implements RoutineProvider {
    public static final String ID = "my-domain/coordinator-routine";

    @Inject RoutineSupport routine;

    @Override
    public List<RoutineDefinition> routines() {
        return List.of(RoutineDefinition.builder(ID)
                .name("Coordinator Routine")
                .description("Decides what work should happen next for the mission.")
                .routeUri("direct:my-domain-coordinator")
                .defaultLane("low-pass")
                .build());
    }

    @Override
    public void configure() {
        from("direct:my-domain-coordinator")
                .process(routine.enterThought("coordinator", "Mission plan and current blockers.", 800))
                .process(routine.decide("needs-worker")
                        .prompt("Does this mission need another worker AgentRun?"))
                .to("direct:decide")
                .process(routine.exitThought("coordinator"));
    }
}
```

### Tool profile example

```java
@ApplicationScoped
public class RepositoryMcpServer {
    public static final String ID = "repository";

    private final McpServerDefinition definition;

    @Inject
    public RepositoryMcpServer(
            @ConfigProperty(name = "agent.tool.mcp.repository.url", defaultValue = "") String url) {
        this.definition = McpServerDefinition.builder(ID)
                .url(url)
                .transportType("streamableHttp")
                .authHeaderName("Authorization")
                .authHeaderValueFormat("token %s")
                .build();
    }

    public McpServerDefinition definition() {
        return definition;
    }
}

@ApplicationScoped
public class SourceToolProfile implements ToolProfileProvider {
    public static final String SOURCE_CONTROL = "source-control";

    private final RepositoryMcpServer repository;

    @Inject
    public SourceToolProfile(RepositoryMcpServer repository) {
        this.repository = repository;
    }

    @Override
    public List<ToolProfile> toolProfiles() {
        return List.of(ToolProfile.builder(SOURCE_CONTROL)
                .mcpServer(repository.definition())
                .denyTools(RepositoryMcpServer.ID, "delete_repository", "delete_branch")
                .maxResultTokens(6000)
                .build());
    }
}
```

If a listed server has no allow-list, its tools are allowed unless explicitly denied. Use `allowTool`, `allowTools`, `denyTool`, `denyTools`, default arguments, and result limits to make profiles narrower. Declare each reusable MCP server definition in its own `McpServer` class, inject that class into the `ToolProfile` classes that need it, and keep capability policy in each profile. The reserved profile id `none` is built into the framework and is intentionally excluded from catalog listings.

### Inference lane example

```java
@ApplicationScoped
public class LowLane implements InferenceLaneProvider {
    @Override
    public InferenceLaneDefinition inferenceLane() {
        return InferenceLaneDefinition.builder("low-pass")
                .requestTopic("llm.requests.low")
                .responseTopic("llm.replies.low")
                .parallelism(1)
                .endpoint("http://localhost:8000/v1")
                .model("qwen-low")
                .apiKeyConfigKey("llm.inference-lane.low.api-key")
                .apiKeyEnvVariable("LLM_LOW_API_KEY")
                .build();
    }
}
```

## Run configuration

### Kafka and LLM

| Environment variable         | Default                    | Purpose                              |
| ---------------------------- | -------------------------- | ------------------------------------ |
| `KAFKA_BOOTSTRAP_SERVERS`    | `localhost:9092`           | Kafka bootstrap servers.             |
| `LLM_LOW_REQUEST_TOPIC`      | `llm.requests.low`         | Low-lane request topic.              |
| `LLM_LOW_REPLY_TOPIC`        | `llm.replies.low`          | Low-lane reply topic.                |
| `LLM_HIGH_REQUEST_TOPIC`     | `llm.requests.high`        | High-lane request topic.             |
| `LLM_HIGH_REPLY_TOPIC`       | `llm.replies.high`         | High-lane reply topic.               |
| `AGENT_RUN_SCHEDULED_TOPIC`  | `agent-runs.scheduled`     | Durable `AgentRunScheduled` start-signal topic. |
| `AGENT_RUN_SCHEDULED_CONSUMER_GROUP` | `agent-run-scheduler` | Consumer group for scheduled agent-run start signals. |
| `AGENT_RUN_SCHEDULED_CONSUMER_ENABLED` | `true`              | Enables the scheduled-event consumer route. |
| `LLM_TOPIC_DEAD_LETTER`      | `llm.dead-letter`          | Failed queued-job topic.             |
| `LLM_KAFKA_PROVISION_TOPICS` | `true`                     | Create configured topics at startup. |
| `LLM_KAFKA_TOPIC_PARTITIONS` | `1`                        | Scheduled-event and dead-letter topic partitions. Lane request topics use lane parallelism; reply topics are single-partition. |
| `LLM_MODEL_DEFAULT_OPENAI_BASE_URL` | `http://localhost:8000/v1` | Global fallback OpenAI-compatible endpoint. |
| `LLM_MODEL_DEFAULT_OPENAI_MODEL` | `qwen-36-27b`           | Global fallback model name. |
| `LLM_MODEL_DEFAULT_OPENAI_API_KEY` | `not-needed`           | Global fallback API key.  |
| `LLM_MODEL_DEFAULT_OPENAI_TEMPERATURE` | `0`                  | Default temperature for lane-created OpenAI chat models. |
| `LLM_MODEL_DEFAULT_OPENAI_MAX_TOKENS` | `4096`               | Default max output tokens for lane-created OpenAI chat models. |
| `LLM_MODEL_DEFAULT_OPENAI_TIMEOUT_MS` | `300000`             | Default OpenAI request timeout. |
| `LLM_MODEL_DEFAULT_OPENAI_STRICT_JSON_SCHEMA` | `true`         | Default strict JSON-schema mode for OpenAI chat models. |
| `LLM_MODEL_DEFAULT_OPENAI_THINKING_OFF_ENABLED` | `true`       | Default `enable_thinking=false` custom parameter for compatible models. |
| `LLM_MODEL_DEFAULT_STRUCTURED_OUTPUT_PROVIDER_SCHEMA_ENABLED` | `false` | Default provider-schema mode for structured output calls. |
| `LLM_MODEL_DEFAULT_TOOL_MEMORY_MAX_TOKENS` | `12000`          | Default ephemeral tool-pass memory window. |
| `LLM_MODEL_DEFAULT_MAX_TOOL_CALLING_ROUND_TRIPS` | `4`       | Default maximum tool-calling round trips per tool pass. |
| `LLM_LOW_MODEL`              | blank                      | Low-lane model override.             |
| `LLM_HIGH_MODEL`             | blank                      | High-lane model override.            |
| `LLM_LOW_PARALLELISM`        | `1`                        | Low-lane worker parallelism and request-topic partition count. |
| `LLM_HIGH_PARALLELISM`       | `1`                        | High-lane worker parallelism and request-topic partition count. |
| `LLM_LOW_PARTITION_KEY_STRATEGY` | `AGENT_RUN`             | Low-lane partition key strategy: `AGENT_RUN`, `MISSION`, or `JOB`. |
| `LLM_HIGH_PARTITION_KEY_STRATEGY` | `AGENT_RUN`            | High-lane partition key strategy: `AGENT_RUN`, `MISSION`, or `JOB`. |
| `LLM_REPLY_TIMEOUT_MS`       | `900000`                   | Synchronous reply wait timeout.      |
| `AGENT_TOOL_PROFILE_WORKSPACE_READONLY_MAX_RESULT_TOKENS` | `4000` | Result budget for the read-only cross-system software-team profile. |
| `AGENT_TOOL_PROFILE_WORKSPACE_COORDINATION_MAX_RESULT_TOKENS` | `5000` | Result budget for the bounded cross-system coordination profile. |


Model selection precedence is:

```text
inference lane model > LLM_MODEL_DEFAULT_OPENAI_MODEL fallback
```

The selected model is copied into LLM request metadata and preserved on worker failures and DLQ entries.

Per-lane endpoints, model names, and API keys override the global fallback values when set:

```bash
export LLM_LOW_ENDPOINT=http://localhost:8000/v1
export LLM_LOW_MODEL=qwen-low
export LLM_LOW_API_KEY=not-needed
export LLM_HIGH_ENDPOINT=http://localhost:8001/v1
export LLM_HIGH_MODEL=qwen-high
export LLM_HIGH_API_KEY=not-needed
```

An inference lane is a shared Kafka-backed model work pool. `parallelism(1)` behaves like the original serialized lane. `parallelism(n)` creates the worker consumer route with `consumersCount=n&maxPollRecords=1`.

When topic provisioning is enabled, missing lane request topics are created with partitions equal to lane parallelism. Existing request topics are not silently expanded; they must already have at least `lane.parallelism()` partitions or startup fails clearly. Reply topics remain single-partition in the current single-orchestrator topology.

Lane-wide FIFO ordering is not guaranteed when `parallelism > 1`. Ordering is preserved only for jobs with the same Kafka partition key. The default partition key strategy is `AGENT_RUN`, with fallback:

```text
agentRunId -> missionId -> jobId
```

Other strategies are:

```text
MISSION: missionId -> agentRunId -> jobId
JOB:     jobId
```

Request messages use the selected partition key as the Kafka key. Reply messages continue to use `jobId` as the Kafka key and are correlated in memory by `jobId`.

Current topology assumption: the request/reply implementation assumes one active orchestrator process owns the in-memory LLM reply waiters. Lane worker parallelism is supported within that topology. Horizontally scaled orchestrator instances require reply-to routing or durable reply storage and are out of scope for this mode.

### AgentRun scheduling

Agent-run scheduling is event-driven:

```text
direct:start-agent-run
  -> validate AgentRunRequest
  -> create/record AgentRun as accepted
  -> publish AgentRunScheduled
  -> return AgentRunHandle

Kafka consumer: AgentRunScheduled
  -> accepted/scheduled -> running when eligible
  -> launch direct:execute-agent-run asynchronously
  -> return so Kafka can commit

direct:execute-agent-run
  -> rebuild mission + AgentRun context
  -> execute the routine route
  -> record complete or failed
```

Duplicate scheduled events are idempotent. Accepted or scheduled runs transition to running and launch once. Running, complete, failed, or otherwise ineligible runs are ignored.

The framework does not impose an AgentRun concurrency limit in this mode. Every eligible scheduled event launches an async routine execution. LLM/model concurrency is controlled separately by inference lanes.

Lifecycle hardening is intentionally future work. AgentRun admission records accepted state and then publishes `AgentRunScheduled`. If publication fails after admission, a run can remain accepted without a scheduled event. That is acceptable for this local/in-memory prototype; a future reconciler should scan stale accepted runs and republish `AgentRunScheduled`.

### Local worker verification

By default, `direct:llm` assumes a local single-process run mode. Before queueing an LLM request, it verifies that the selected lane has a local worker route and a local reply-consumer route available.

For an API process that queues jobs while another process runs the workers, disable the local worker check:

```bash
export LLM_WORKER_ENABLED=false
export LLM_LOCAL_WORKER_ROUTE_VERIFICATION_ENABLED=false
```

This skips the selected lane worker-route verification only. The local response consumer is still required because the current API remains synchronous request/reply.

## MCP configuration

MCP servers are optional. Framework code builds clients from generic `McpServerDefinition` entries attached to the active `ToolProfile`. The bundled software-team profiles use individual example-side `PlaneMcpServer`, `GiteaMcpServer`, and `MattermostMcpServer` classes for connection definitions; each `ToolProfile` class composes the immutable definitions it needs and owns its own capability policy. Adopters can add other servers by adding a `McpServerDefinition` through `ToolProfile.builder(...).mcpServer(...)`, without editing `McpClientFactory` or defining LangChain4j MCP server beans. `ToolProfile.server(...)` only declares a logical server id for allow/deny/default-argument rules; it does not create a buildable MCP client by itself.

If a server URL or persona token is blank, that MCP client is skipped.

```bash
export MATTERMOST_MCP_URL=http://localhost:8001/mcp
export GITEA_MCP_URL=http://localhost:8002/mcp
export PLANE_MCP_URL=http://localhost:8003/mcp
export PLANE_WORKSPACE_SLUG=my-workspace

export NOAH_MATTERMOST_TOKEN=...
export OWEN_MATTERMOST_TOKEN=...
export ETHAN_MATTERMOST_TOKEN=...
export MILES_MATTERMOST_TOKEN=...

export NOAH_GITEA_TOKEN=...
export OWEN_GITEA_TOKEN=...
export ETHAN_GITEA_TOKEN=...
export MILES_GITEA_TOKEN=...

export NOAH_PLANE_TOKEN=...
export OWEN_PLANE_TOKEN=...
export ETHAN_PLANE_TOKEN=...
export MILES_PLANE_TOKEN=...
```

Additional identity configuration examples are available in `docs/local-agent-identities.env.example` and `docs/local-mcp-agent-identities.md`.

## Workspace API

The workspace API is intended for local development and debugging.

| Endpoint           | Method | Purpose                                    |
| ------------------ | ------ | ------------------------------------------ |
| `/missions`        | `POST` | Accept and schedule a new mission.         |
| `/workspace/view`  | `GET`  | Inspect catalog and agent-run projections. |
| `/workspace/reset` | `POST` | Reset local workspace state.               |

`POST /missions` returns `202 Accepted` after mission creation, accepted run recording, and scheduled-event publication. Routine execution starts when `AgentRunScheduled` is consumed and continues asynchronously after the Kafka route commits. Later routine failures are visible through workspace projection state and normal logs, not as a delayed HTTP response.

The projection includes mission IDs, agent-run IDs, persona/routine metadata, primitive state, selected inference lane, latest results, timeline entries, and thought-stack snapshots.

## Project layout

| Path                                        | Purpose                                             |
| ------------------------------------------- | --------------------------------------------------- |
| `src/main/java/us/nusun/llmshare/framework` | Reusable framework code.                            |
| `src/main/java/us/nusun/llmshare/example`   | Bundled software-team example catalog and routines. |
| `src/main/resources/schemas`                | JSON schemas used by primitives and examples.       |
| `docs/architecture.md`                      | Additional architecture notes.                      |
| `docs/local-agent-identities.env.example`   | Local persona identity template.                    |
| `docs/local-mcp-agent-identities.md`        | MCP identity setup notes.                           |

## Development

Run tests:

```bash
./mvnw test
```

Run with coverage:

```bash
./mvnw verify
```

Component tests should fake LLM and MCP behavior at route boundaries. They should not require live vLLM, Kafka, Plane, Gitea, Mattermost, or MCP servers unless they are explicitly integration tests.

## Design boundaries

Keep reusable framework behavior separate from example-team behavior.

Framework code owns:

* `AgentRunRequest` / `AgentRunHandle`
* `direct:start-agent-run`
* `direct:act`, `direct:decide`, and `direct:create`
* `ThoughtStack`
* `AgentRunProjection`
* inference lanes
* tool-profile enforcement and MCP client wrapping

Example or application namespaces own:

* personas
* routines
* prompts and schemas
* tool-profile choices
* domain orchestration patterns such as the bundled Director routine

## Troubleshooting

### A mission fails before the first LLM call

Check that `AGENT_FRAMEWORK_INITIAL_AGENT_RUN_PERSONA_ID` and `AGENT_FRAMEWORK_INITIAL_AGENT_RUN_ROUTINE_ID` point to catalog entries that exist at startup.

### `direct:llm` says a lane route is not available

In local mode, the selected lane must have both a worker route and a response-consumer route running. Check `LLM_WORKER_ENABLED`, `LLM_LOCAL_WORKER_ROUTE_VERIFICATION_ENABLED`, and the configured inference lanes.

### MCP tools do not appear

Confirm that the MCP server URL is set, the persona has a token for that server, and the selected `ToolProfile` includes the server/tool.

### Tool results are truncated

Tool result size is bounded by the selected `ToolProfile.maxResultTokens` when set, otherwise by `AGENT_MCP_MAX_TOOL_RESULT_TOKENS`. Set a profile-specific limit when one workflow needs more context than the global default.
