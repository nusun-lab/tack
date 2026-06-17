# Local MCP Agent Identities

This wiring is for local inner-dev only.

## What Is Configured

All software-team roles still share one physical LLM backend and one LLM worker lane:

- one Kafka request topic: `llm.requests`
- one Kafka worker route: `llm-worker`
- one Kafka-backed request/reply worker lane

The role-specific runtime change in this repo is only the MCP identity wiring.

## Required Local Env Vars

Start from [docs/local-agent-identities.env.example](/home/aphelio/Code/llm-share-camel-kafka/docs/local-agent-identities.env.example).

Required endpoint variables:

- `MATTERMOST_MCP_URL`
- `GITEA_MCP_URL`
- `PLANE_MCP_URL`
- `PLANE_WORKSPACE_SLUG`

Optional transport/auth override variables:

- `MATTERMOST_MCP_TRANSPORT_TYPE`
- `GITEA_MCP_TRANSPORT_TYPE`
- `PLANE_MCP_TRANSPORT_TYPE`
- `MATTERMOST_AUTH_HEADER_NAME`
- `GITEA_AUTH_HEADER_NAME`
- `PLANE_AUTH_HEADER_NAME`
- `MATTERMOST_AUTH_HEADER_VALUE_FORMAT`
- `GITEA_AUTH_HEADER_VALUE_FORMAT`
- `PLANE_AUTH_HEADER_VALUE_FORMAT`
- `MATTERMOST_MCP_DISABLED_TOOLS`
- `GITEA_MCP_DISABLED_TOOLS`
- `PLANE_MCP_DISABLED_TOOLS`

Role token variables:

- `DIRECTOR_MATTERMOST_TOKEN`
- `DIRECTOR_GITEA_TOKEN`
- `DIRECTOR_PLANE_TOKEN`
- `PM_MATTERMOST_TOKEN`
- `PM_GITEA_TOKEN`
- `PM_PLANE_TOKEN`
- `PO_MATTERMOST_TOKEN`
- `PO_GITEA_TOKEN`
- `PO_PLANE_TOKEN`
- `DEV_MATTERMOST_TOKEN`
- `DEV_GITEA_TOKEN`
- `DEV_PLANE_TOKEN`

## Identity Model

Roles:

- `software-team/director`
- `software-team/project-manager`
- `software-team/product-owner`
- `software-team/application-developer`

Per system:

- Mattermost uses distinguishable bot tokens under your own Mattermost identity for this phase.
- Gitea uses real per-role onboarded users with role-specific PATs.
- Plane uses real per-role onboarded users with role-specific API tokens.

The code does not assume identical identity semantics across systems.

## Plane Header Behavior

The Plane MCP client sends both headers on every request:

- `x-api-key: <role Plane token>`
- `x-workspace-slug: <PLANE_WORKSPACE_SLUG>`

## MCP Logging

Application-level MCP logging is enabled by default. Tool discovery and tool execution are logged without auth headers so MCP interaction is visible during local analysis.

Duplicate MCP tool names are filtered at agent-configuration time with Camel's `withMcpToolProviderFilter(...)`. The default local inner-dev config keeps Mattermost `get_me` and filters `get_me` from Gitea and Plane so the merged MCP tool namespace stays unique.

Optional env toggles:

- `AGENT_MCP_LOGGING_ENABLED=true`
- `AGENT_MCP_LOG_TRANSPORT_REQUESTS=false`
- `AGENT_MCP_LOG_TRANSPORT_RESPONSES=false`

`AGENT_MCP_LOG_TRANSPORT_REQUESTS` stays off by default because the legacy HTTP/SSE transport can log request headers, which would risk credential exposure.

## Local Configuration Path

All MCP endpoint and identity values are runtime config only:

- [application.properties](/home/aphelio/Code/llm-share-camel-kafka/src/main/resources/application.properties)
- local env file sourced into your shell

Credentials are not copied into Kafka job data and are not intended to be logged.

## Running Locally

Example:

```bash
set -a
. ./docs/local-agent-identities.env.example
# replace placeholders in your real local env file before using it
set +a
./mvnw quarkus:dev
```

Use your real local env file instead of the example placeholders.

## Backstage

Backstage MCP is intentionally not implemented in this change. Add it later as another runtime MCP service in the same factory layer.

## Deferred Deployment Wiring

Kubernetes and OpenShift Secret wiring is intentionally out of scope for this local-only change and will be added later.
