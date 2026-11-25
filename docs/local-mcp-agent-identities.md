# Local MCP Agent Identities

This wiring is for local development with the bundled software-team example.

Personas declare external identities. MCP clients are created from:

```text
Persona identity + ToolProfile server + McpServerDefinition
```

The token config path and token environment variable are person-named. Routine roles do not own identity tokens.

The software-team example declares Plane, Gitea, and Mattermost as separate `PlaneMcpServer`, `GiteaMcpServer`, and `MattermostMcpServer` classes. Each tool profile class injects only the MCP server declarations it needs, composes their immutable definitions directly, and keeps its own allow/deny/default-argument policy.

## Required MCP Server Variables

Start from `docs/local-agent-identities.env.example`.

Endpoint variables:

- `MATTERMOST_MCP_URL`
- `GITEA_MCP_URL`
- `PLANE_MCP_URL`
- `PLANE_WORKSPACE_SLUG`

The example server definitions use streamable HTTP. Auth formats are fixed in code:

- Mattermost: `Authorization: Bearer <token>`
- Gitea: `Authorization: token <token>`
- Plane: `Authorization: Bearer <token>`
- Plane also sends `x-workspace-slug: <PLANE_WORKSPACE_SLUG>` when configured.

## Software-Team Profiles

See [Example tool profiles](../README.md#example-tool-profiles) for the current profile IDs and their roles.

Plane is authoritative for projects, planned work, assignments, status, dependencies, priorities, blockers, and delivery planning. Gitea is authoritative for repositories, files, branches, commits, pull requests, builds, tags, and releases. Mattermost is for conversation, questions, handoffs, and concise notifications.

Gitea issues, labels, milestones, and time tracking are intentionally unavailable because Plane owns planned work and status. Mattermost messages should link to authoritative Plane or Gitea artifacts and should not recreate durable state.

## Persona Token Variables

Example namespace personas:

- Noah: Director persona
- Owen: project manager persona
- Ethan: product owner persona
- Miles: application developer persona

Token variables:

- `NOAH_MATTERMOST_TOKEN`
- `NOAH_GITEA_TOKEN`
- `NOAH_PLANE_TOKEN`
- `OWEN_MATTERMOST_TOKEN`
- `OWEN_GITEA_TOKEN`
- `OWEN_PLANE_TOKEN`
- `ETHAN_MATTERMOST_TOKEN`
- `ETHAN_GITEA_TOKEN`
- `ETHAN_PLANE_TOKEN`
- `MILES_MATTERMOST_TOKEN`
- `MILES_GITEA_TOKEN`
- `MILES_PLANE_TOKEN`

Corresponding config paths:

```properties
persona.noah.identity.mattermost.token=${NOAH_MATTERMOST_TOKEN:}
persona.owen.identity.gitea.token=${OWEN_GITEA_TOKEN:}
persona.ethan.identity.plane.token=${ETHAN_PLANE_TOKEN:}
persona.miles.identity.plane.token=${MILES_PLANE_TOKEN:}
```

## InferenceLane Tokens

Model endpoint tokens are separate from persona MCP tokens.

```bash
export LLM_LOW_API_KEY=...
export LLM_HIGH_API_KEY=...
```

`LLM_MODEL_DEFAULT_OPENAI_API_KEY` is the fallback when a lane-specific token is not configured.

## Identity Model

The persona is the source of truth for external identity mappings:

```java
@ConfigProperty(name = "persona.owen.identity.gitea.token", defaultValue = "")
String giteaToken = "";

// ...
.identity("gitea", giteaToken, "owen-agent")
```

The handle is a tool-facing hint. The token is a concrete value supplied by the adopter's provider, usually from whatever config property or secret mapping that application chooses. Blank tokens skip that persona/server MCP client only.

## MCP Logging

Application-level MCP logging is available without logging auth headers.

Optional env toggles:

- `AGENT_MCP_LOG_TRANSPORT_REQUESTS=false`
- `AGENT_MCP_LOG_TRANSPORT_RESPONSES=false`

Transport request logging should stay off unless you have confirmed the transport does not expose credentials.
