# LLM Share Camel Kafka

`llm-share-camel-kafka` is a Quarkus + Apache Camel framework for running mission-bound agents as structured Camel workflows.

The framework does not treat an agent as a single LangChain4j object. In this project:

```text
AgentRun = Mission + Persona + Routine + working state
```

LangChain4j/Camel agents are lower-level execution details. The framework gives you reusable primitives for routines, catalog-discovered personas/runtimes/tools, scoped thought state, and dashboard-safe projections.

## What You Build

A project built on this framework usually defines a catalog:

| Catalog Item | What It Answers | Example |
| --- | --- | --- |
| `Persona` | Who is acting? | `software-team/owen`, a project manager persona |
| `RoutineDefinition` | What workflow does that persona run? | `software-team/project-manager` -> `direct:example-software-team-project-manager` |
| `ToolProfile` | Which MCP servers/tools are visible for an action? | `project-management` -> Plane tools minus destructive deletes |
| `InferenceLane` | Where does LLM work queue and execute? | `low-pass` -> `llm.requests.low` / local vLLM endpoint |
| `ExecutionRuntime` | How does a primitive invoke the model? | `tool-low`, `synthesis-low` |

A mission starts from a user goal. An `AgentRun` starts with one persona and one routine. Routines then use framework primitives:

```text
direct:act      -> tool/action pass + StepResult synthesis
direct:decide   -> boolean DecisionResult for route control
direct:create   -> schema-shaped JSON creation
direct:start-agent-run -> generic child AgentRun start
```

## Why The Catalog Matters

The catalog is the framework's runtime inventory. It is not hard-coded into framework routes.

Quarkus discovers catalog entries from CDI beans that implement provider interfaces:

```text
PersonaProvider
RoutineProvider
ToolProfileProvider
InferenceLaneProvider
ExecutionRuntimeProvider
RuntimeTopologyProvider
```

At startup, registries collect all providers and fail fast on duplicate IDs or invalid runtime/lane references.

The framework uses the catalog for:

- validating `StartAgentRunCommand.personaId` and `routineId`
- making catalog data available to routines as normal Camel/CDI beans
- resolving routine route URIs for `direct:start-agent-run`
- resolving runtime and lane metadata for LLM calls
- resolving tool profiles before MCP calls
- exposing workspace/catalog state through `/workspace/view`

## Bundled Example Catalog

The current bundled namespace is `us.nusun.llmshare.example`. It demonstrates a software team, not a framework requirement.

| Person | Persona Id | Routine Id | Purpose |
| --- | --- | --- | --- |
| Noah | `software-team/noah` | `software-team/director` | Example orchestration persona |
| Owen | `software-team/owen` | `software-team/project-manager` | Coordination and project setup |
| Ethan | `software-team/ethan` | `software-team/product-owner` | Scope and acceptance criteria |
| Miles | `software-team/miles` | `software-team/application-developer` | Implementation and delivery |

The Director is just an example routine. It demonstrates orchestration by composing a `direct:create` prompt with Camel Simple catalog bean expressions, receiving generic `StartAgentRunCommand` objects, and sending them to generic `direct:start-agent-run`.

You can remove the Director pattern entirely and start child runs from any routine, API route, scheduler, or event handler.

## Configure The Starting AgentRun

`direct:start-mission` creates the first `AgentRun` from two config properties:

```properties
agent.framework.initial-agent-run.persona-id=${AGENT_FRAMEWORK_INITIAL_AGENT_RUN_PERSONA_ID:software-team/noah}
agent.framework.initial-agent-run.routine-id=${AGENT_FRAMEWORK_INITIAL_AGENT_RUN_ROUTINE_ID:software-team/director}
```

To run a different catalog namespace, provide different IDs:

```bash
export AGENT_FRAMEWORK_INITIAL_AGENT_RUN_PERSONA_ID=my-domain/coordinator
export AGENT_FRAMEWORK_INITIAL_AGENT_RUN_ROUTINE_ID=my-domain/coordinator-routine
```

Those IDs must exist in the discovered `PersonaCatalog` and `RoutineCatalog`.

## Defining Catalog Entries

### Persona

A persona defines prompt identity, display metadata, and external identity token mappings. It does not directly own tools.

```java
@ApplicationScoped
public class CoordinatorPersona implements PersonaProvider {
    @Override
    public List<Persona> personas() {
        return List.of(Persona.builder("my-domain/coordinator")
                .name("Coordinator")
                .description("Coordinates mission work and starts useful child AgentRuns.")
                .trait("Keeps plans concrete and reports blockers clearly.")
                .label("COORD")
                .color("38;2;100;180;255")
                .identity("gitea", "agent.identity.coordinator.gitea.token", "COORDINATOR_GITEA_TOKEN", "coordinator-agent")
                .build());
    }
}
```

`agentRunStartAssignable(false)` keeps a persona out of `personaCatalog.agentRunStartAssignable()` and `personaCatalog.agentRunStartAssignableJson()`. The bundled Director persona uses that setting so orchestration prompts that reference assignable personas do not ask it to start another Director by default.

### Routine

A routine is a Camel route plus catalog metadata.

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
                .process(routine.decide("needs-work")
                        .prompt("Does this mission need another worker AgentRun?"))
                .to("direct:decide")
                .process(routine.exitThought("coordinator"));
    }
}
```

Routines should compose framework primitives and local route logic. Avoid moving domain-specific behavior into framework core.

### ToolProfile

A tool profile defines the MCP surface available during `direct:act`.

```java
@ApplicationScoped
public class SourceToolProfile implements ToolProfileProvider {
    @Override
    public List<ToolProfile> toolProfiles() {
        return List.of(ToolProfile.builder("source-control")
                .server("gitea")
                .denyTools("gitea", "delete_repository", "delete_branch")
                .maxResultCharacters(6000)
                .build());
    }
}
```

ToolProfile semantics are broad by default for listed servers: if a server is visible and no allow-list is declared, tools are allowed unless explicitly denied. This is intentional so users can try MCP servers without knowing every tool name up front. Use `denyTools(...)`, `allowTool(...)`, default arguments, and result limits to tighten profiles.

### InferenceLane And ExecutionRuntime

An inference lane owns queue placement and model endpoint placement. An execution runtime chooses a lane and whether tool calling is enabled.

```java
@ApplicationScoped
public class LowLane implements InferenceLaneProvider {
    @Override
    public InferenceLaneDefinition inferenceLane() {
        return InferenceLaneDefinition.builder("low-pass")
                .requestTopic("llm.requests.low")
                .responseTopic("llm.replies.low")
                .concurrency(1)
                .endpoint("http://localhost:8000/v1")
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
                .agentName("synthesis-low")
                .agentFactoryBeanName("executionRuntimeAgentFactory")
                .build();
    }
}
```

The bundled example provides:

```text
low-pass, high-pass
tool-low, tool-high
synthesis-low, synthesis-high
```

## Framework Primitives

### `direct:start-agent-run`

Starts a generic child or top-level `AgentRun` from `StartAgentRunCommand`.

```json
{
  "missionId": "mission-1",
  "personaId": "software-team/owen",
  "routineId": "software-team/project-manager",
  "parentAgentRunId": "run-parent",
  "reason": "Coordinate setup and delivery risks.",
  "input": {
    "role": "pm",
    "config": { "priority": "high" },
    "tags": ["planning", "delivery"]
  }
}
```

`input` is arbitrary structured JSON and is preserved as maps/lists/booleans/numbers/nulls in the child run working state and route body.

`AgentRunExchangeScope` keeps parent and child run state isolated, including thoughts, current primitive state, latest results, and projection timelines.

### `direct:act`

Use `direct:act` for work that may involve tools and then must become a universal `StepResult`.

Protocol:

```text
resolve tool and synthesis runtimes
mark current act
prepare tool/action pass prompt
queue LLM call through selected runtime/lane
capture tool/action result
prepare StepResult synthesis prompt
queue synthesis LLM call
parse StepResult
rewrite writable ThoughtStack frames
update AgentRun and projection
```

A routine usually sets the act properties through `RoutineSupport`:

```java
.process(routine.act("source-readiness")
        .prompt("Confirm the target repository exists and report blockers.")
        .toolProfile("source-control"))
.to("direct:act")
```

### `direct:decide`

Use `direct:decide` for narrow control-flow questions. It returns a `DecisionResult` and mirrors the boolean result for Camel DSL use:

```text
exchange property: decisions.<decisionId>
exchange property: decision.<decisionId>.result
```

Example:

```java
.process(routine.decide("done")
        .prompt("Is this routine complete or blocked for this pass?"))
.to("direct:decide")
.choice()
    .when(simple("${exchangeProperty[decision.done.result]} == true"))
        .stop()
.end()
```

### `direct:create`

Use `direct:create` for schema-shaped JSON generation.

It is generic: it does not inject persona, routine, or tool-profile catalog data. The caller supplies the already-composed prompt and schema, and receives a schema-valid JSON object.

```java
.process(routine.create("mvp-slice")
        .schemaId("mvp-slice-schema")
        .schemaJson("""
                {"type":"object","properties":{"component":{"type":"string"}},"required":["component"]}
                """)
        .prompt("Create a small MVP slice description."))
.to("direct:create")
```

If a routine wants catalog data in the prompt, compose it explicitly with Camel Simple. The framework exposes the catalogs as normal beans:

```text
${bean:personaCatalog.agentRunStartAssignableJson}
${bean:routineCatalog.startableJson}
${bean:toolProfileCatalog.allJson}
```

The bundled Director example uses that pattern:

```java
.process(routine.create("initial-agent-runs")
        .schema("classpath:schemas/example/software-team/start-agent-run-commands-schema.json")
        .prompt(simple("""
                Given this mission, propose the initial software-team
                AgentRuns to start as generic StartAgentRunCommand objects.

                Mission:
                ${exchangeProperty.mission.goal}

                Personas:
                ${bean:personaCatalog.agentRunStartAssignableJson}

                Routines:
                ${bean:routineCatalog.startableJson}

                Tool profiles:
                ${bean:toolProfileCatalog.allJson}

                Return only schema-compliant JSON.
                """)))
.to("direct:create")
```

This keeps the primitive generic: catalogs provide data, Simple inserts data, and `direct:create` consumes the resulting prompt.

## ThoughtStack And Projection

`ThoughtStack` is exchange-local working context. Routines push frames around routine scopes or loop scopes. `direct:act` can rewrite the top writable frames using `StepResult.thoughts`.

Thought content is synthesized working context, not hidden chain-of-thought.

`AgentRunProjection` is the dashboard/API view. It includes sanitized observability state:

```text
agentRunId, missionId, parentAgentRunId, status
persona, routine
current primitive/id/prompt summary
toolProfile
resolved runtimes and lanes
full thought content
latest result
timeline
```

Read projections through:

```bash
curl -s http://localhost:8080/workspace/view | jq '.agentRunProjections'
```

## Runtime Configuration

### Kafka And LLM

Minimum local configuration:

```bash
export KAFKA_BOOTSTRAP_SERVERS=localhost:9092
export LLM_WORKER_ENABLED=true
export LLM_KAFKA_PROVISION_TOPICS=true
export OPENAI_BASE_URL=http://localhost:8000/v1
export OPENAI_MODEL=qwen-36-27b
export OPENAI_API_KEY=not-needed
```

Per-lane overrides:

```bash
export LLM_LOW_ENDPOINT=http://localhost:8000/v1
export LLM_LOW_API_KEY=not-needed
export LLM_HIGH_ENDPOINT=http://localhost:8001/v1
export LLM_HIGH_API_KEY=not-needed
```

Relevant config keys:

```properties
llm.inference-lane.low.request-topic=${LLM_LOW_REQUEST_TOPIC:llm.requests.low}
llm.inference-lane.low.reply-topic=${LLM_LOW_REPLY_TOPIC:llm.replies.low}
llm.inference-lane.low.endpoint=${LLM_LOW_ENDPOINT:}
llm.inference-lane.low.api-key=${LLM_LOW_API_KEY:}
llm.inference-lane.high.request-topic=${LLM_HIGH_REQUEST_TOPIC:llm.requests.high}
llm.inference-lane.high.reply-topic=${LLM_HIGH_REPLY_TOPIC:llm.replies.high}
llm.inference-lane.high.endpoint=${LLM_HIGH_ENDPOINT:}
llm.inference-lane.high.api-key=${LLM_HIGH_API_KEY:}
```

If a lane endpoint or key is blank, the shared OpenAI-compatible fallback is used:

```properties
llm.openai.base-url=${LLM_OPENAI_BASE_URL:${OPENAI_BASE_URL:http://localhost:8000/v1}}
llm.openai.api-key=${LLM_OPENAI_API_KEY:${OPENAI_API_KEY:not-needed}}
llm.openai.model=${LLM_OPENAI_MODEL:${OPENAI_MODEL:qwen-36-27b}}
```

### MCP Servers And Persona Tokens

MCP servers are optional. If a server URL or persona token is blank, that MCP client is skipped.

Only streamable HTTP MCP transport is supported. The `*_MCP_TRANSPORT_TYPE` environment variables default to `streamableHttp`; non-streamable values are ignored with a warning.

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

A template is available at [docs/local-agent-identities.env.example](docs/local-agent-identities.env.example).

## Running The Bundled Scenario

Start the app:

```bash
./mvnw quarkus:dev
```

Submit a mission:

```bash
curl -s -X POST http://localhost:8080/goals \
  -H 'content-type: application/json' \
  --data '{
    "goal": "Build a small parking lot manager app. The team should clarify scope, coordinate work, and start one implementation path."
  }' | jq
```

Expected response:

```json
{
  "missionId": "mission-...",
  "status": "accepted",
  "projection": "run-..."
}
```

Inspect the workspace and catalog:

```bash
curl -s http://localhost:8080/workspace/view | jq
```

Reset between runs:

```bash
curl -s -X POST http://localhost:8080/workspace/reset | jq
```

## Example Scenario Flow

```text
POST /goals
  -> direct:submit-user-goal
  -> direct:start-mission
  -> initial AgentRun from configured persona/routine IDs
  -> bundled default: Noah runs software-team Director
  -> Director uses direct:create with an explicit Simple-composed catalog prompt
  -> create result contains generic StartAgentRunCommand objects
  -> each child starts through direct:start-agent-run
  -> PM / PO / Developer routines use direct:act, direct:decide, direct:create
  -> projections update at framework boundaries
```

Component tests exercise this flow with fake LLM/tool responses at the Camel route boundary. They do not require live vLLM, Kafka, Plane, Gitea, Mattermost, or MCP servers.

## Boundary Rules

Keep framework and namespace responsibilities separate:

```text
Framework core:
  StartAgentRunCommand
  direct:start-agent-run
  direct:act / direct:decide / direct:create
  ThoughtStack
  AgentRunProjection
  ExecutionRuntime / InferenceLane
  ToolProfile / ProfiledMcpClient

Example or user namespace:
  personas
  routines
  schemas/prompts
  tool-profile choices
  orchestration patterns such as Director
```

Do not put software-team assumptions into framework core. Do not put concrete tool operational knowledge into persona traits; keep that in routines and tool profiles.

## Tests

Run all tests:

```bash
./mvnw test
```

Tests should fake LLM and MCP interactions with Camel test wiring, route advice, or direct primitive fixtures. There is no production mock mode.
