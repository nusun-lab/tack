# OpenShell persona sandboxes

Tack can use OpenShell as the execution boundary for repository work without moving the Tack runtime itself into a sandbox.

## Execution model

A sandbox belongs to one **mission + persona** pair. The same persona reuses that sandbox across its AgentRuns for the mission, so repository checkouts and other working state survive routine boundaries. Different missions or personas get different sandboxes.

Tack records the sandbox name in AgentRun working state and uses `/sandbox/repos` as the repository working area.

`sandbox_exec` is a normal Tack tool, but it is injected only when the selected `ToolProfile` explicitly allows logical server `sandbox` and tool `sandbox_exec`. A profile that does not allow it cannot execute sandbox commands.

For the bundled software-team example, `source-control-write` enables `sandbox_exec`. Repository contents are cloned, edited, built, tested, diffed, committed, and pushed with ordinary tools inside the sandbox. Gitea tools remain useful for repository discovery and provisioning, branch/commit/pull-request metadata, releases, and remote verification.

## Gitea identity

When a persona has a Gitea external identity, Tack creates or updates an OpenShell provider for that persona, attaches it to the sandbox, and configures Git identity and credential handling in the sandbox.

The Gitea endpoint is derived from the AgentRun repository URL when available. `GITEA_URL` is an optional fallback.

## Network policy

Developer sandboxes allow outbound package access to:

- `repo.maven.apache.org`
- `maven.repository.redhat.com`
- `registry.npmjs.org`
- `pypi.org`
- `files.pythonhosted.org`

The npm rule enables `allow_encoded_slash`, which is required for scoped package paths such as `@scope/package`.

Package access is still policy-controlled. A package host outside this set is not implicitly reachable.

## Filesystem policy

Tack preserves OpenShell's restrictive filesystem baseline and adds `/home/tooling` as read-only so the Devfile UDI's Java, Maven, Gradle, and Node toolchains are usable without making the image toolchain writable.

Repository work remains under `/sandbox`. Common writable state is redirected there as well:

- Maven user home: `/sandbox/.m2`
- Maven local repository: `/sandbox/.m2/repository`
- Gradle user home: `/sandbox/.gradle`
- npm cache: `/sandbox/.npm`
- global Git config: `/sandbox/.gitconfig`

This keeps the developer toolchain immutable while allowing dependency caches, repository state, and Git identity configuration to persist with the persona sandbox.

## Configuration

| Environment variable | Default | Purpose |
| --- | --- | --- |
| `AGENT_SANDBOX_OPENSHELL_ENABLED` | `false` | Enable persona sandboxes |
| `OPENSHELL_EXECUTABLE` | `openshell` | OpenShell executable used by lifecycle operations |
| `OPENSHELL_GATEWAY_ENDPOINT` | `http://openshell.tack.svc.cluster.local:8080` | OpenShell gRPC gateway |
| `OPENSHELL_CONTROL_TIMEOUT_SECONDS` | `180` | Sandbox/provider control-operation timeout |
| `OPENSHELL_EXEC_TIMEOUT_SECONDS` | `120` | Default `sandbox_exec` command timeout |
| `OPENSHELL_SANDBOX_IMAGE` | `quay.io/devfile/universal-developer-image:ubi9-latest` | OCI image Tack requests for persona sandboxes |
| `GITEA_URL` | empty | Optional Gitea endpoint fallback |

The 120-second execution timeout matters for builds, package installation, and test suites. Raise `OPENSHELL_EXEC_TIMEOUT_SECONDS` when a legitimate command needs more time rather than assuming a timed-out command failed inside the sandbox.

## Lifecycle and reset

Tack sends the configured sandbox image in the OpenShell create request rather than inheriting the gateway's default workload image.

Persona sandboxes are persistent working environments, not AgentRun-scoped temporary processes. Tack discovers and reuses the mission/persona sandbox on later AgentRuns.

`POST /workspace/reset` also asks the sandbox lifecycle manager to discover and delete Tack-owned sandboxes. Reset therefore clears both in-memory Tack state and persistent Tack sandbox state instead of leaving prior experiment checkouts behind.

## Troubleshooting

### Sandbox never appears

Confirm `AGENT_SANDBOX_OPENSHELL_ENABLED=true`, the gateway endpoint is reachable, and the running act selected a profile that actually permits sandbox execution. Sandbox creation is lazy and occurs when sandbox-backed tooling is needed.

### `sandbox_exec` is unavailable

The selected `ToolProfile` must explicitly allow `sandbox/sandbox_exec`. Read-only or MCP-only profiles do not receive the tool.

### Git authentication fails

Confirm the persona has a Gitea identity token and handle, the repository URL points at the expected Gitea instance, and `GITEA_URL` is correct when fallback discovery is required. Provider attachment prepares sandbox Git credentials; ordinary Git then performs clone, fetch, commit, and push operations.

### Package installation cannot reach a registry

Check the requested host against the sandbox package allowlist. npm scoped packages also depend on the `allow_encoded_slash` policy setting for `registry.npmjs.org`.

### A sandbox command times out

The default execution timeout is 120 seconds. Increase `OPENSHELL_EXEC_TIMEOUT_SECONDS` for valid long-running commands and keep individual agent execution steps focused.

### Reset leaves old work behind

Use `POST /workspace/reset` and inspect reset output for sandbox destruction. Reset discovers Tack-owned sandboxes before deleting them, so cleanup is not limited to sandbox names still present in process memory.
