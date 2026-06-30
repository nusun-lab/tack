# Local MCP Agent Identities

This wiring is for local development.

Personas declare external identities. MCP clients are created from:

```text
Persona identity + ToolProfile server + McpServerDefinition
```

The token config path and token environment variable are person-named. Routine roles do not own identity tokens.

Mattermost, Gitea, and Plane are bundled example MCP server definitions declared by the example tool profiles. Framework adopters can add different servers in their own `ToolProfile` entries without changing `McpClientFactory`.

## Required MCP Server Variables

Start from `docs/local-agent-identities.env.example`.

Endpoint variables:

- `MATTERMOST_MCP_URL`
- `GITEA_MCP_URL`
- `PLANE_MCP_URL`
- `PLANE_WORKSPACE_SLUG`

Transport/auth override variables:

- `MATTERMOST_MCP_TRANSPORT_TYPE`
- `GITEA_MCP_TRANSPORT_TYPE`
- `PLANE_MCP_TRANSPORT_TYPE`
- `MATTERMOST_AUTH_HEADER_NAME`
- `GITEA_AUTH_HEADER_NAME`
- `PLANE_AUTH_HEADER_NAME`
- `MATTERMOST_AUTH_HEADER_VALUE_FORMAT`
- `GITEA_AUTH_HEADER_VALUE_FORMAT`
- `PLANE_AUTH_HEADER_VALUE_FORMAT`

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
agent.identity.noah.mattermost.token=${NOAH_MATTERMOST_TOKEN:}
agent.identity.owen.gitea.token=${OWEN_GITEA_TOKEN:}
agent.identity.ethan.plane.token=${ETHAN_PLANE_TOKEN:}
agent.identity.miles.plane.token=${MILES_PLANE_TOKEN:}
```

## InferenceLane Tokens

Model endpoint tokens are separate from persona MCP tokens.

```bash
export LLM_LOW_API_KEY=...
export LLM_HIGH_API_KEY=...
```

The global `OPENAI_API_KEY` remains a fallback when a lane-specific token is not configured.

## Identity Model

The persona is the source of truth for external identity mappings:

```java
@ConfigProperty(name = "agent.identity.owen.gitea.token", defaultValue = "")
String giteaToken = "";

// ...
.identity("gitea", giteaToken, "owen-agent")
```

The handle is a tool-facing hint. The token is a concrete value supplied by the adopter's provider, usually from whatever config property or secret mapping that application chooses. Blank tokens skip that persona/server MCP client.

## Plane Header Behavior

The Plane MCP client sends:

- configured auth header containing the persona Plane token. The application default is `Authorization: Bearer <token>`.
- `x-workspace-slug: <PLANE_WORKSPACE_SLUG>` when configured

## MCP Logging

Application-level MCP logging is available without logging auth headers.

Optional env toggles:

- `AGENT_MCP_LOG_TRANSPORT_REQUESTS=false`
- `AGENT_MCP_LOG_TRANSPORT_RESPONSES=false`

Transport request logging should stay off unless you have confirmed the transport does not expose credentials.
