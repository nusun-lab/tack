# OpenShell persona sandboxes

Tack can use OpenShell as the execution boundary for repository work without moving the Tack runtime itself into a sandbox.

## Execution model

A sandbox belongs to one **mission + persona** pair. The same persona reuses that sandbox across its AgentRuns for the mission, so repository checkouts and other working state survive routine boundaries. Different missions or personas get different sandboxes.

Tack records the sandbox name on the AgentRun and uses `/sandbox/repos` as the repository working area.

Tack prepares the sandbox synchronously during admission for both initial mission runs and later AgentRuns. It waits for OpenShell readiness, activates the developer package policy, prepares the Java and Maven environment, and completes any required Gitea provider attachment, Git network authorization, identity, and credential setup before publishing `AgentRunScheduled`. If preparation fails or times out, the affected admission publishes no scheduled events.

`sandbox_exec` is a normal Tack tool, but it is injected only when the selected `ToolProfile` explicitly allows logical server `sandbox` and tool `sandbox_exec`. A profile that does not allow it cannot execute sandbox commands.

For the bundled software-team example, `source-control-write` enables `sandbox_exec`. Repository contents are cloned, edited, built, tested, diffed, committed, and pushed with ordinary tools inside the sandbox. Gitea tools remain useful for repository discovery and provisioning, branch/commit/pull-request metadata, releases, and remote verification.

## Gitea identity

When a persona has a Gitea external identity, Tack creates or updates an OpenShell provider for that persona, attaches it to the sandbox, and configures Git identity and credential handling in the sandbox.

Tack uses `GITEA_URL` when configured. Otherwise it derives the endpoint from the AgentRun repository URL.

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

Each `sandbox_exec` call selects the UBI9 Universal Developer Image's bundled JDK 21 after the login shell has loaded its profiles. It uses the image's `JAVA_HOME_21` or locates a Java 21 installation in its SDKMAN candidates, then exports `JAVA_HOME` and places that JDK first on `PATH`. This makes `java`, `javac`, and Maven use the same JDK in each call. Custom images should expose their JDK 21 through `JAVA_HOME_21` or their default Java environment.

When OpenShell supplies a sandbox CA, Tack copies the JDK truststore to `/sandbox/.m2/tack-cacerts` and imports the sandbox CA into the copy. Maven uses that truststore and OpenShell's proxy for repository downloads.

## Configuration

| Environment variable | Default | Purpose |
| --- | --- | --- |
| `AGENT_SANDBOX_OPENSHELL_ENABLED` | `false` | Enable persona sandboxes |
| `OPENSHELL_EXECUTABLE` | `openshell` | OpenShell executable used by lifecycle operations |
| `OPENSHELL_GATEWAY_ENDPOINT` | `http://openshell.tack.svc.cluster.local:8080` | OpenShell gRPC gateway |
| `OPENSHELL_CONTROL_TIMEOUT_SECONDS` | `180` | Ordinary sandbox/provider control RPC and policy activation wait timeout |
| `OPENSHELL_PROVISION_TIMEOUT_SECONDS` | `900` | Readiness wait budget per sandbox, shared by all watch connections in that wait |
| `OPENSHELL_EXEC_TIMEOUT_SECONDS` | `120` | Default `sandbox_exec` command timeout |
| `OPENSHELL_SANDBOX_IMAGE` | `quay.io/devfile/universal-developer-image:ubi9-latest` | OCI image Tack requests for persona sandboxes |
| `GITEA_URL` | empty | Optional Gitea endpoint override |

The 120-second execution timeout matters for builds, package installation, and test suites. Raise `OPENSHELL_EXEC_TIMEOUT_SECONDS` when a legitimate command needs more time rather than assuming a timed-out command failed inside the sandbox.

## Lifecycle and reset

Tack sends the configured sandbox image in the OpenShell create request rather than inheriting the gateway's default workload image.

Persona sandboxes are persistent working environments, not AgentRun-scoped temporary processes. Tack discovers and reuses the mission/persona sandbox on later AgentRuns.

Tack creates a missing sandbox and waits for readiness. If the sandbox is already provisioning, Tack watches that sandbox within the same per-attempt budget. An already-ready sandbox goes straight to initialization. Terminal phases fail admission. A readiness timeout leaves the sandbox in place for inspection and retry. Retry with the same mission ID and persona to reuse it. Initialization still runs on recovery, using named policy merges and existing providers so retrying preserves unrelated rules and workspace state.

`POST /workspace/reset` also asks the sandbox lifecycle manager to discover and delete Tack-owned sandboxes. Reset therefore clears both in-memory Tack state and persistent Tack sandbox state instead of leaving prior experiment checkouts behind.

## Troubleshooting

### Sandbox never appears

Confirm `AGENT_SANDBOX_OPENSHELL_ENABLED=true` and that the gateway is reachable. Check Tack's admission logs for the mission, persona, sandbox name, failed preparation stage, elapsed time, applicable timeout, and last known phase and conditions. Sandbox preparation occurs during admission. The selected tool profile controls whether the admitted run can call `sandbox_exec`.

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

## Deployment smoke test

Use a staging deployment with OpenShell enabled and a persona routine that can call `sandbox_exec`. For each attempt record the sandbox workload's actual image digest (container status `imageID`), pod `spec.runtimeClassName` or the platform default, Tack's readiness `elapsedMs`, and the full HTTP admission time.

1. Send a cold-start request to the normal `POST /missions` endpoint with a fresh `requestId` header and one selected persona. Use a fresh ID so the sandbox has an empty workspace and Maven cache. Use `curl -w '%{http_code} %{time_total}\n'` or equivalent to record the status and admission time. Confirm HTTP 202 and confirm the readiness and preparation log entries precede the scheduled event and routine start. Record whether the sandbox node had the image cached.
2. Have the routine use Tack's `sandbox_exec` to create the fixture below under `/sandbox/repos/tack-maven-smoke` and run `java -version && javac -version && mvn -version && mvn -B clean test` there. Verify Java 21 for all three tools, the test passes, and Commons Lang was downloaded to `/sandbox/.m2/repository/org/apache/commons/commons-lang3/3.17.0/`.
3. After the first run finishes, send another `POST /missions` request with the same `requestId` and persona. Have this run use a separate `sandbox_exec` call in the existing fixture directory to run `java -version && javac -version && mvn -version && mvn -B -o clean test`. Verify the sandbox name is unchanged, readiness is immediate, Java is still 21, and the offline build passes without recreating the fixture. Record the warm admission time separately.
4. To validate timeout recovery, use a short provisioning budget on staging and arrange a sandbox startup longer than that budget. Verify the failed admission publishes no scheduled event and leaves the sandbox available for inspection. After it becomes ready, retry with the same request ID and persona. Verify developer dependency downloads and any configured Git clone work before marking the smoke test complete.

Set the external HTTP client's timeout and the OpenShift Route or proxy budget to cover total synchronous admission. One request may wait for several personas in sequence and for post-readiness policy, provider, and Git operations. Size those budgets from observed cold-start timings and the configured stage timeouts. Apply cluster-level timeout changes as a deployment follow-up.

Use this fixture `pom.xml`.

```xml
<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>us.nusun</groupId>
  <artifactId>tack-maven-smoke</artifactId>
  <version>1.0.0</version>
  <properties>
    <maven.compiler.release>21</maven.compiler.release>
  </properties>
  <dependencies>
    <dependency>
      <groupId>org.apache.commons</groupId>
      <artifactId>commons-lang3</artifactId>
      <version>3.17.0</version>
    </dependency>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <version>5.11.4</version>
      <scope>test</scope>
    </dependency>
  </dependencies>
  <build>
    <plugins>
      <plugin>
        <artifactId>maven-compiler-plugin</artifactId>
        <version>3.14.0</version>
      </plugin>
      <plugin>
        <artifactId>maven-surefire-plugin</artifactId>
        <version>3.5.3</version>
      </plugin>
    </plugins>
  </build>
</project>
```

Save this as `src/test/java/SmokeTest.java`. `List.getFirst()` also checks that the test compiles against the Java 21 API.

```java
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SmokeTest {
    @Test
    void linksDownloadedDependencyAndRunsOnJava21() {
        assertEquals(21, Runtime.version().feature());
        assertEquals("Tack", StringUtils.capitalize(List.of("tack").getFirst()));
    }
}
```
