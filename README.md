# LLM Share: Camel + Kafka

## Technical

### What It Is

A Quarkus + Camel application that runs LLM work through a single shared Kafka-backed request/reply lane.

Core constraints:

- every LLM call shares one model endpoint
- Kafka is the transport and serialization point
- the app does not expose internal workflow concepts in its public API
- agent routines are written as Camel routes

### Public API

The public surface is intentionally simple:

- `POST /goals`: submit a plain-language goal
- `POST /workspace/reset`: clear Kafka topic contents for a fresh run

A goal request goes through the internal director flow, then through the shared LLM lane:

```text
POST /goals
    -> internal director route
    -> direct:ask-llm
    -> Kafka request topic or mock worker
    -> shared LLM worker
    -> Kafka response topic
    -> correlated reply returned to caller
```

### Build And Run

Environment:

```bash
export OPENAI_BASE_URL=https://qwen-ai-llm.apps.cluster.lab.nusun.us/v1
export OPENAI_MODEL=qwen-36-27b
export OPENAI_API_KEY=not-needed
export KAFKA_BOOTSTRAP_SERVERS=localhost:9092
```

Run locally:

```bash
./mvnw quarkus:dev
```

Focused tests:

```bash
./mvnw -q -DskipITs test -Dtest=ApiRouteTest,DirectorFlowTest,DirectorParsingTest,KafkaTopicProvisionerTest
```

### Important Configuration

Local MCP identity setup is documented in [docs/local-mcp-agent-identities.md](/home/aphelio/Code/llm-share-camel-kafka/docs/local-mcp-agent-identities.md).

Key properties live in [application.properties](src/main/resources/application.properties).

```properties
kafka.bootstrap.servers=${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
llm.topic.requests=${LLM_TOPIC_REQUESTS:llm.requests}
llm.topic.responses=${LLM_TOPIC_RESPONSES:llm.responses}
llm.worker.enabled=${LLM_WORKER_ENABLED:true}
llm.mock.enabled=${LLM_MOCK_ENABLED:false}
llm.reply-timeout-ms=${LLM_REPLY_TIMEOUT_MS:300000}
llm.openai.base-url=${LLM_OPENAI_BASE_URL:${OPENAI_BASE_URL:http://localhost:8000/v1}}
llm.openai.model=${LLM_OPENAI_MODEL:${OPENAI_MODEL:qwen-36-27b}}
llm.log.ansi-enabled=${LLM_LOG_ANSI_ENABLED:true}
```

### Logging And Shutdown

- agent prompts and wake-up lines are dimmed in the agent color
- agent responses stay bright in the agent color
- fast shutdown is enabled on purpose
- Kafka forced-shutdown stack traces are suppressed so shutdown stays readable

## Agent Development

### Current Structure

Route builders for personas live under [`src/main/java/us/nusun/llmshare/agent`](src/main/java/us/nusun/llmshare/agent).

That package is now the primary place where persona metadata lives.

Important pieces:

- [Director.java](src/main/java/us/nusun/llmshare/agent/Director.java): asks for role missions and dynamically fans them out
- [TeamPatterns.java](src/main/java/us/nusun/llmshare/agent/TeamPatterns.java): shared routine-step and done-check behavior
- [AgentPersonaDefinition.java](src/main/java/us/nusun/llmshare/team/AgentPersonaDefinition.java): first-class persona metadata
- [AgentPersonaProvider.java](src/main/java/us/nusun/llmshare/team/AgentPersonaProvider.java): discovery contract for routebuilder-owned personas
- [AgentPersonaRegistry.java](src/main/java/us/nusun/llmshare/team/AgentPersonaRegistry.java): startup-discovered persona registry used by runtime, dashboard, and director prompts
- [SoftwareTeamContext.java](src/main/java/us/nusun/llmshare/team/SoftwareTeamContext.java): shared team operating context, separate from persona definitions

The director returns a JSON object shaped like:

```json
{
  "software-team/project-manager": "Coordinate the work and reduce delivery risk.",
  "software-team/product-owner": "Clarify scope and acceptance criteria.",
  "software-team/application-developer": "Drive implementation and expose blockers."
}
```

Keys are agent string identifiers. Values are missions.

The director may leave a role out if that role is not needed for the goal.

### Adding A New Persona

The intended workflow is code-first, with the routebuilder as the main source of truth.

Happy path:

1. Create a new `RouteBuilder` in `src/main/java/us/nusun/llmshare/agent`.
2. Implement `AgentPersonaProvider`.
3. Define one `AgentPersonaDefinition` constant in that class.
4. Put the persona system prompt in that definition as a Java text block.
5. Add `RoutineStepDefinition` entries in that definition.
6. Add optional `platformIdentities` key/value mappings such as Mattermost, Gitea, or Plane handles.
7. Define the route body in Camel DSL.
8. Add any required runtime token/env wiring using the project’s naming convention.
9. Start the app and verify the persona appears in `/workspace/view`.
10. Add or update tests.

A normal persona definition should provide:

- stable `roleId`
- `roleSlug`
- `displayName`
- `routeUri`
- `routeId`
- `agentLabel`
- `ansiColor`
- `capabilitySummary`
- `systemPrompt`
- `platformIdentities`
- `routineSteps`

Runtime registration, dashboard display, scratchpad visibility, logging metadata, and director role guides are derived from the discovered persona definitions.

Useful shared routes:

- `direct:log-agent-wakeup`
- `direct:log-software-team-step`
- `direct:software-team-routine-step`
- `direct:software-team-check-done`

### Minimal Persona Pattern

```java
import us.nusun.llmshare.team.AgentPersonaDefinition;
import us.nusun.llmshare.team.AgentPersonaProvider;
import us.nusun.llmshare.team.RoutineStepDefinition;

@ApplicationScoped
public class SecurityReviewer extends RouteBuilder implements AgentPersonaProvider {
    private static final AgentPersonaDefinition PERSONA = new AgentPersonaDefinition(
            "software-team/security-reviewer",
            "security-reviewer",
            "Security Reviewer",
            "direct:software-team-security-reviewer",
            "software-team-security-reviewer",
            "SEC",
            "38;2;220;120;120",
            "Security Reviewer reporting for duty.",
            "Security Reviewer standing down.",
            "Review security risks, trust boundaries, and abuse cases.",
            """
            You are the Security Reviewer in a small software team.
            Review one concrete security concern at a time.
            Return valid JSON only when asked for structured output.
            """,
            Map.of(
                    "mattermost", "@security-reviewer",
                    "gitea", "security-reviewer-bot"
            ),
            List.of(
                    new RoutineStepDefinition("threat-scan", "Review one threat boundary.")
            ),
            true,
            true
    );

    @Override
    public AgentPersonaDefinition persona() {
        return PERSONA;
    }

    @Override
    public void configure() {
        from(PERSONA.routeUri())
                .routeId(PERSONA.routeId())
                .setHeader("agent", constant(PERSONA.roleId()))
                .setHeader("role", constant(PERSONA.roleSlug()))
                .setHeader("agentLabel", constant(PERSONA.agentLabel()))
                .setHeader("wakeupMessage", constant(PERSONA.wakeupMessage()))
                .setHeader("sleepMessage", constant(PERSONA.sleepMessage()))
                .setHeader("systemPrompt", constant(PERSONA.systemPrompt()));

        // define the rest of the routine here in Camel DSL
    }
}
```

### Team Context vs Persona Context

Keep these separate:

- persona definition: who the agent is
- team operating context: how the team works, shared tools, owner identity, shared coordination channel, and collaboration constraints

That shared team context lives in [SoftwareTeamContext.java](src/main/java/us/nusun/llmshare/team/SoftwareTeamContext.java) instead of being duplicated into every persona prompt.

## Running A Scenario

### Submit A Goal

```bash
curl -s -X POST http://localhost:8080/goals \
  -H 'content-type: application/json' \
  --data '{
    "goal": "We need a basic internal baby-tracking web app for feeds, diapers, sleep, and wake events. Give the team one concise mission each so they can start work today."
  }' | jq
```

Response shape:

```json
{
  "software-team/project-manager": "Coordinate the work and reduce delivery risk.",
  "software-team/product-owner": "Clarify scope and acceptance criteria.",
  "software-team/application-developer": "Drive implementation and expose blockers."
}
```

### Reset The Workspace

```bash
curl -s -X POST http://localhost:8080/workspace/reset | jq
```

Response shape:

```json
{
  "status": "RESET",
  "topics": [
    "llm.requests",
    "llm.responses",
    "llm.dead-letter"
  ],
  "jobsCleared": false,
  "message": "Kafka topics were reset. No in-memory job store is maintained."
}
```
