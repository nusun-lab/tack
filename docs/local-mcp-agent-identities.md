# Software-team MCP identities

The bundled example uses the existing persona account configuration to connect to
Kaneo, Gitea, and Mattermost over Streamable HTTP. Start from
[the environment template](local-agent-identities.env.example). Each server uses
the authenticated persona's credential. Keep credentials in your secret store.

## Kaneo provisioning

Use the dedicated **Tack Software Team** workspace. Provision six distinct Kaneo users
whose emails match the corresponding `PERSONA_<NAME>_EMAIL` settings. Grant each
persona workspace-wide access so it inherits access to existing and future mission
projects. Verify the six user IDs with `whoami` and `list_workspace_members`.

| Persona | Kaneo workspace permissions |
| --- | --- |
| Noah | Viewer |
| Owen | Read, create and update projects; create, read, update and assign tasks; create task labels; write comments and manage relations. The provisioned Owen account owns this dedicated workspace. |
| Ethan and Quinn | Member permissions plus `task: assign`. The provisioned workspace uses the `tack-planner` role. |
| Avery and Miles | Member |

Issue a separate native MCP OAuth access token as each persona using the site's
authorization-code flow with PKCE. Kaneo 2.50.0 issues 30-day bearer tokens and
advertises only the authorization-code grant. Rotate ahead of expiration by
authorizing again and restarting Tack with the replacement secret. Tack sends
the supplied bearer token directly to the native endpoint. Developer API keys
are for other Kaneo surfaces. [Kaneo 2.50.0 MCP authorization](https://github.com/usekaneo/kaneo/blob/v2.50.0/apps/api/src/mcp/index.ts)

## Deployment configuration

| Variable | Value |
| --- | --- |
| `KANEO_MCP_URL` | `https://kaneo.apps.cluster.lab.nusun.us/api/mcp` |
| `NOAH_KANEO_TOKEN` | Noah's MCP OAuth token |
| `OWEN_KANEO_TOKEN` | Owen's MCP OAuth token |
| `ETHAN_KANEO_TOKEN` | Ethan's MCP OAuth token |
| `AVERY_KANEO_TOKEN` | Avery's MCP OAuth token |
| `MILES_KANEO_TOKEN` | Miles's MCP OAuth token |
| `QUINN_KANEO_TOKEN` | Quinn's MCP OAuth token |

These map to `agent.tool.mcp.kaneo.url` and `persona.<name>.identity.kaneo.token`.
The OpenShift `tack/tack-kaneo` Secret contains these runtime settings. Reference
it as an additional source in the Tack container's `envFrom` at cutover:

```yaml
envFrom:
  - secretRef:
      name: tack
  - secretRef:
      name: tack-kaneo
```

Persona sign-in credentials for token rotation are stored separately in
`projects/tack-kaneo-persona-logins`. Only the runtime bearer credentials go into
Tack. Keep `AGENT_MCP_LOG_TRANSPORT_REQUESTS` and
`AGENT_MCP_LOG_TRANSPORT_RESPONSES` disabled. Keep existing Gitea, Mattermost,
OpenShell, Kafka, and inference settings. Gitea uses `Authorization: token` and
Mattermost uses `Authorization: Bearer`. Model API keys are configured separately
using `LLM_LOW_API_KEY` and `LLM_HIGH_API_KEY`.

The [profile table](../README.md#example-tool-profiles) describes which routine
uses each profile.
