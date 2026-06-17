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

Route builders for agents live under [`src/main/java/us/nusun/llmshare/agent`](src/main/java/us/nusun/llmshare/agent).

That package should stay focused on Camel route definitions.

Route-builder pieces:

- [Director.java](src/main/java/us/nusun/llmshare/agent/Director.java): asks for role missions and dynamically fans them out
- [TeamPatterns.java](src/main/java/us/nusun/llmshare/agent/TeamPatterns.java): shared routine-step and done-check behavior

Role-catalog support lives separately under [`src/main/java/us/nusun/llmshare/team`](src/main/java/us/nusun/llmshare/team):

- [TeamRoleDefinition.java](src/main/java/us/nusun/llmshare/team/TeamRoleDefinition.java): metadata the director needs about a role
- [TeamRoleRegistry.java](src/main/java/us/nusun/llmshare/team/TeamRoleRegistry.java): tracks the currently available roles

The director no longer returns a fixed three-field object. It returns a JSON object shaped like:

```json
{
  "software-team/project-manager": "Coordinate the work and reduce delivery risk.",
  "software-team/product-owner": "Clarify scope and acceptance criteria.",
  "software-team/application-developer": "Drive implementation and expose blockers."
}
```

Keys are agent string identifiers. Values are missions.

The director may leave a role out if that role is not needed for the goal.

### Adding A New Role

The intended workflow is code-first, not config-heavy.

1. Create a new `RouteBuilder` in `src/main/java/us/nusun/llmshare/agent`.
2. Add one `TeamRoleDefinition` constant in that class.
3. Register it in `configure()` with `teamRoleRegistry.register(...)`.
4. Define the route body in Camel DSL.
5. Add the role's system prompt to [application.properties](src/main/resources/application.properties).

A role route should provide:

- a stable role id such as `software-team/security-reviewer`
- a `direct:` route URI that the director can dispatch to
- an ANSI label for logging
- a wake-up message
- a short capability summary for the director prompt
- a system prompt

A role route should also follow these conventions:

- set header `agent` to the role id
- set header `role` to a simple internal name
- set header `agentLabel` for colorized logs
- set header `wakeupMessage` for the wake-up line
- set header `systemPrompt`
- read its mission from `exchangeProperty.objective`
- reuse the shared routes in [TeamPatterns.java](src/main/java/us/nusun/llmshare/agent/TeamPatterns.java)

Useful shared routes:

- `direct:log-agent-wakeup`
- `direct:log-software-team-step`
- `direct:software-team-routine-step`
- `direct:software-team-check-done`

### Minimal Role Pattern

```java
import us.nusun.llmshare.team.TeamRoleDefinition;
import us.nusun.llmshare.team.TeamRoleRegistry;

@ApplicationScoped
public class SecurityReviewer extends RouteBuilder {
    private static final TeamRoleDefinition ROLE = new TeamRoleDefinition(
            "software-team/security-reviewer",
            "Security Reviewer",
            "direct:software-team-security-reviewer",
            "SEC",
            "Security Reviewer reporting for duty. Threat model is forming.",
            "Review security risks, trust boundaries, and abuse cases.",
            "{{agents.software-team.security-reviewer.system-prompt}}"
    );

    @Inject
    TeamRoleRegistry teamRoleRegistry;

    @Override
    public void configure() {
        teamRoleRegistry.register(ROLE);

        from("direct:software-team-security-reviewer")
                .routeId("software-team-security-reviewer")
                .setHeader("agent", constant(ROLE.roleId()))
                .setHeader("role", constant("security-reviewer"))
                .setHeader("agentLabel", constant(ROLE.agentLabel()))
                .setHeader("wakeupMessage", constant(ROLE.wakeupMessage()))
                .setHeader("systemPrompt", constant("{{agents.software-team.security-reviewer.system-prompt}}"))
                .setProperty("priorSummary", constant("No prior Security Reviewer summary yet."))
                .setProperty("cycleCount", constant(0))
                .setProperty("done", constant(false))
                .to("direct:log-agent-wakeup");

        // define the rest of the role's routine here in Camel DSL
    }
}
```

### Removing Or Renaming A Role

- removing a role: remove its route class and prompt property
- renaming a role: change the role id, route URI, and any prompt property names together

The director only offers roles that register themselves at startup.

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
