# LLM Share Camel Kafka

A Quarkus and Apache Camel framework for running mission-bound LLM agents through structured workflows, Kafka-backed model execution, and optional MCP tools.

The framework treats an agent run as application state, not as a single long-lived LangChain4j object. Camel routes define the workflow, Kafka serializes model work, and the catalog describes the personas, routines, tool profiles, inference lanes, and execution runtimes available to the system.

## Highlights

* **Mission-bound agent runs** with isolated parent/child state.
* **Catalog-driven configuration** for personas, routines, tool profiles, inference lanes, and execution runtimes.
* **Kafka-backed LLM queueing** so model calls can be serialized and routed by lane.
* **Reusable Camel primitives** for action, decision, schema-shaped creation, and child agent startup.
* **Two-pass tool execution** for `direct:act`: one pass for tool use, one pass for structured `StepResult` synthesis.
* **Optional MCP integration** with persona-specific credentials, tool filtering, default arguments, and bounded tool results.
* **Workspace projection API** for inspecting active missions and agent runs during local development.

## Architecture

A mission moves through the framework in a small number of explicit steps:

1. `POST /missions` accepts a goal plus one or more explicit `AgentRunRequest` entries.
2. The mission route creates a mission, schedules each requested `AgentRun`, and returns accepted handles quickly.
3. Scheduled routines run outside the HTTP request thread as normal Camel routes.
4. Routines call framework primitives such as `direct:act`, `direct:decide`, `direct:create`, and `direct:start-agent-run`.
5. `direct:start-agent-run` schedules more agent runs and returns accepted handles immediately.
6. LLM work is queued through Kafka by inference lane.
7. Replies return through Kafka and update the agent run projection.

At runtime, a mission is a goal/context container. An `AgentRun` is one persona/routine execution scheduled inside that mission. Parent/child agent runs are lineage only; no agent run synchronously waits for another agent run to complete.

## Requirements

* Java 21
* Maven
* Kafka
* An OpenAI-compatible chat/completions endpoint, such as vLLM
* Optional MCP server definitions declared by tool profiles. The bundled example profiles include Mattermost, Gitea, and Plane.

The project currently uses Quarkus 3.36.0 and a snapshot Camel LangChain4j agent dependency because endpoint-level agent configuration and JSON schema support are still ahead of the released Camel Quarkus line used by the rest of the application.

## Quick start

Start Kafka and your OpenAI-compatible model endpoint, then configure the local runtime:

```bash
export KAFKA_BOOTSTRAP_SERVERS=localhost:9092

export OPENAI_BASE_URL=http://localhost:8000/v1
export OPENAI_MODEL=qwen-36-27b
export OPENAI_API_KEY=not-needed

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

Mission requests explicitly choose which agent runs to schedule. There is no framework-level default, root, or initial persona/routine. To start the bundled example, submit Noah on the Director routine in the `agentRuns` array.

## Core concepts

### Mission

A mission is the top-level goal/context container. It owns the mission ID, goal text, status, and metadata. It schedules one or more agent runs.

### AgentRun

An `AgentRun` is one execution of one persona on one routine. It carries working state, current primitive state, thought-stack state, latest results, and projection data. Agent runs may schedule more agent runs. Parent/child means lineage, not blocking. Starting an agent run returns an accepted handle immediately; output is eventual projection/result state.

### Persona

A persona defines the actor: name, description, behavioral traits, display metadata, and external identity mappings. It does not directly own tools.

### Routine

A routine is a Camel route plus catalog metadata. Routines should compose framework primitives and domain-specific route logic. Framework core should stay generic.

### ToolProfile

A tool profile defines which MCP servers and tools are visible during `direct:act`. Profiles can declare MCP server connection settings, allow tools, deny tools, add default arguments, and cap returned tool-result size. If an act omits a profile, the framework uses an internal no-tools profile; adopters do not need to publish a `none` profile.

### InferenceLane

An inference lane defines where LLM jobs are queued and which model endpoint/model serves them. The bundled example provides `low-pass` and `high-pass` lanes.

### ExecutionRuntime

An execution runtime selects an inference lane and model behavior, such as whether tool calling is enabled. It can also override the lane model. The bundled example provides `tool-low`, `tool-high`, `synthesis-low`, and `synthesis-high`.

## Framework primitives

### `direct:act`

Use `direct:act` when the model may need tools and the routine needs a structured `StepResult` afterward.

```java
.process(routine.act("source-readiness")
        .prompt("Confirm the target repository exists and report blockers.")
        .toolProfile("source-control"))
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
                Given this mission, propose the initial software-team AgentRuns.

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

The framework supplies mission id, agent run id, parent lineage, status, timestamps, and lifecycle projection data. The route returns an accepted `AgentRunHandle` immediately and does not wait for the scheduled routine to complete. The `input` object is preserved as structured working state for the scheduled run.

## Defining catalog entries

Catalog entries are discovered from CDI beans. The main provider interfaces are:

| Provider                   | Registers                 |
| -------------------------- | ------------------------- |
| `PersonaProvider`          | Personas                  |
| `RoutineProvider`          | Routine definitions       |
| `ToolProfileProvider`      | MCP tool profiles         |
| `InferenceLaneProvider`    | LLM queue/model lanes     |
| `ExecutionRuntimeProvider` | Runtime definitions       |
| `RuntimeTopologyProvider`  | Runtime topology metadata |

## Create your own catalog

At minimum, a framework adopter provides:

* `PersonaProvider` for the actors available to missions.
* `RoutineProvider` for Camel routes those actors can run.
* `InferenceLaneProvider` for queue topics, endpoint overrides, and optional lane model names.
* `ExecutionRuntimeProvider` for runtime IDs that select lanes, tool-calling behavior, and optional runtime model overrides.
* Initial persona/routine selection in each `POST /missions` request through one or more `agentRuns` entries.

Optional pieces:

* `ToolProfileProvider` when routines need MCP tools. Tool profiles can include `McpServerDefinition` entries for each server they expose, including concrete URL, transport, auth header format, extra headers, default tool arguments, deny lists, and result limits.

### Persona example

```java
@ApplicationScoped
public class CoordinatorPersona implements PersonaProvider {
    @ConfigProperty(name = "agent.identity.coordinator.gitea.token", defaultValue = "")
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
                .defaultToolRuntime("tool-low")
                .defaultSynthesisRuntime("synthesis-low")
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
public class SourceToolProfile implements ToolProfileProvider {
    @ConfigProperty(name = "agent.mcp.gitea.url", defaultValue = "")
    String giteaUrl = "";

    @Override
    public List<ToolProfile> toolProfiles() {
        return List.of(ToolProfile.builder("source-control")
                .mcpServer(McpServerDefinition.builder("gitea")
                        .url(giteaUrl)
                        .authHeaderValueFormat("token %s")
                        .build())
                .denyTools("gitea", "delete_repository", "delete_branch")
                .maxResultCharacters(6000)
                .build());
    }
}
```

If a listed server has no allow-list, its tools are allowed unless explicitly denied. Use `allowTool`, `allowTools`, `denyTool`, `denyTools`, default arguments, and result limits to make profiles narrower. The reserved profile id `none` is built into the framework and is intentionally excluded from catalog listings.

### Inference lane and runtime examples

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

@ApplicationScoped
public class SynthesisLow implements ExecutionRuntimeProvider {
    @Override
    public ExecutionRuntimeDefinition executionRuntime() {
        return ExecutionRuntimeDefinition.builder("synthesis-low")
                .lane("low-pass")
                .toolCalling(false)
                .model("qwen-runtime-override")
                .agentName("synthesis-low")
                .agentFactoryBeanName("executionRuntimeAgentFactory")
                .build();
    }
}
```

## Runtime configuration

### Kafka and LLM

| Environment variable         | Default                    | Purpose                              |
| ---------------------------- | -------------------------- | ------------------------------------ |
| `KAFKA_BOOTSTRAP_SERVERS`    | `localhost:9092`           | Kafka bootstrap servers.             |
| `LLM_LOW_REQUEST_TOPIC`      | `llm.requests.low`         | Low-lane request topic.              |
| `LLM_LOW_REPLY_TOPIC`        | `llm.replies.low`          | Low-lane reply topic.                |
| `LLM_HIGH_REQUEST_TOPIC`     | `llm.requests.high`        | High-lane request topic.             |
| `LLM_HIGH_REPLY_TOPIC`       | `llm.replies.high`         | High-lane reply topic.               |
| `LLM_TOPIC_DEAD_LETTER`      | `llm.dead-letter`          | Failed queued-job topic.             |
| `LLM_KAFKA_PROVISION_TOPICS` | `true`                     | Create configured topics at startup. |
| `LLM_KAFKA_TOPIC_PARTITIONS` | `1`                        | Dead-letter topic partitions. Lane request topics use lane parallelism; reply topics are single-partition. |
| `OPENAI_BASE_URL`            | `http://localhost:8000/v1` | Fallback OpenAI-compatible endpoint. |
| `OPENAI_MODEL`               | `qwen-36-27b`              | Fallback model name.                 |
| `OPENAI_API_KEY`             | `not-needed`               | Fallback API key.                    |
| `LLM_LOW_MODEL`              | blank                      | Low-lane model override.             |
| `LLM_HIGH_MODEL`             | blank                      | High-lane model override.            |
| `LLM_LOW_PARALLELISM`        | `1`                        | Low-lane worker parallelism and request-topic partition count. |
| `LLM_HIGH_PARALLELISM`       | `1`                        | High-lane worker parallelism and request-topic partition count. |
| `LLM_LOW_PARTITION_KEY_STRATEGY` | `AGENT_RUN`             | Low-lane partition key strategy: `AGENT_RUN`, `MISSION`, or `JOB`. |
| `LLM_HIGH_PARTITION_KEY_STRATEGY` | `AGENT_RUN`            | High-lane partition key strategy: `AGENT_RUN`, `MISSION`, or `JOB`. |
| `LLM_REPLY_TIMEOUT_MS`       | `900000`                   | Synchronous reply wait timeout.      |

Model selection precedence is:

```text
execution runtime model override > inference lane model > OPENAI_MODEL fallback
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

Current topology assumption: the request/reply implementation assumes one active orchestrator/runtime instance owns the in-memory LLM reply waiters. Lane worker parallelism is supported within that topology. Horizontally scaled orchestrator instances require reply-to routing or durable reply storage and are out of scope for this mode.

### Local worker verification

By default, `direct:llm` assumes a local single-process runtime. Before queueing an LLM request, it verifies that the selected lane has a local worker route and a local reply-consumer route available.

For an API process that queues jobs while another process runs the workers, disable the local worker check:

```bash
export LLM_WORKER_ENABLED=false
export LLM_LOCAL_WORKER_ROUTE_VERIFICATION_ENABLED=false
```

This skips the selected lane worker-route verification only. The local response consumer is still required because the current API remains synchronous request/reply.

## MCP configuration

MCP servers are optional. Framework code builds clients from generic `McpServerDefinition` entries attached to the active `ToolProfile`. The bundled example profiles define Mattermost, Gitea, and Plane servers; adopters can add other servers by adding a `McpServerDefinition` through `ToolProfile.builder(...).mcpServer(...)`, without editing `McpClientFactory` or defining LangChain4j MCP server beans. `ToolProfile.server(...)` only declares a logical server id for allow/deny/default-argument rules; it does not create a buildable MCP client by itself.

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
| `/goals`           | `POST` | Deprecated alias for `/missions`.          |
| `/workspace/view`  | `GET`  | Inspect catalog and agent-run projections. |
| `/workspace/reset` | `POST` | Reset local workspace state.               |

`POST /missions` returns `202 Accepted` after mission creation and agent-run scheduling. Scheduled routines continue asynchronously. Later routine failures are visible through workspace projection state and normal logs, not as a delayed HTTP response. `/goals` remains a deprecated compatibility alias and uses the same explicit request shape.

The projection includes mission IDs, agent-run IDs, persona/routine metadata, primitive state, runtime/lane information, latest results, timeline entries, and thought-stack snapshots.

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
* inference lanes and execution runtimes
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

Tool result size is bounded by the selected `ToolProfile.maxResultCharacters` when set, otherwise by `AGENT_MCP_MAX_TOOL_RESULT_CHARACTERS`. Set a profile-specific limit when one workflow needs more context than the global default.
