package us.nusun.tack.framework.sandbox;

import io.grpc.Context;
import io.grpc.Deadline;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.quarkus.grpc.GrpcClient;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import us.nusun.tack.framework.sandbox.grpc.AddNetworkRule;
import us.nusun.tack.framework.sandbox.grpc.AttachSandboxProviderRequest;
import us.nusun.tack.framework.sandbox.grpc.CreateProviderRequest;
import us.nusun.tack.framework.sandbox.grpc.CreateSandboxRequest;
import us.nusun.tack.framework.sandbox.grpc.ExecSandboxEvent;
import us.nusun.tack.framework.sandbox.grpc.ExecSandboxRequest;
import us.nusun.tack.framework.sandbox.grpc.FilesystemPolicy;
import us.nusun.tack.framework.sandbox.grpc.GetProviderProfileRequest;
import us.nusun.tack.framework.sandbox.grpc.GetProviderRequest;
import us.nusun.tack.framework.sandbox.grpc.GetSandboxPolicyStatusRequest;
import us.nusun.tack.framework.sandbox.grpc.GetSandboxRequest;
import us.nusun.tack.framework.sandbox.grpc.ImportProviderProfilesRequest;
import us.nusun.tack.framework.sandbox.grpc.LandlockPolicy;
import us.nusun.tack.framework.sandbox.grpc.NetworkBinary;
import us.nusun.tack.framework.sandbox.grpc.NetworkEndpoint;
import us.nusun.tack.framework.sandbox.grpc.NetworkPolicyRule;
import us.nusun.tack.framework.sandbox.grpc.ObjectMeta;
import us.nusun.tack.framework.sandbox.grpc.OpenShellGrpc;
import us.nusun.tack.framework.sandbox.grpc.PolicyMergeOperation;
import us.nusun.tack.framework.sandbox.grpc.PolicyStatus;
import us.nusun.tack.framework.sandbox.grpc.Provider;
import us.nusun.tack.framework.sandbox.grpc.ProviderProfile;
import us.nusun.tack.framework.sandbox.grpc.ProviderProfileCategory;
import us.nusun.tack.framework.sandbox.grpc.ProviderProfileCredential;
import us.nusun.tack.framework.sandbox.grpc.ProviderProfileDiscovery;
import us.nusun.tack.framework.sandbox.grpc.ProviderProfileImportItem;
import us.nusun.tack.framework.sandbox.grpc.Sandbox;
import us.nusun.tack.framework.sandbox.grpc.SandboxPhase;
import us.nusun.tack.framework.sandbox.grpc.SandboxPolicy;
import us.nusun.tack.framework.sandbox.grpc.SandboxSpec;
import us.nusun.tack.framework.sandbox.grpc.SandboxStreamEvent;
import us.nusun.tack.framework.sandbox.grpc.SandboxTemplate;
import us.nusun.tack.framework.sandbox.grpc.UpdateConfigRequest;
import us.nusun.tack.framework.sandbox.grpc.UpdateProviderRequest;
import us.nusun.tack.framework.sandbox.grpc.WatchSandboxRequest;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class OpenShellGrpcClient {
    static final String WORKSPACE = "default";
    private static final List<String> DEVELOPER_PACKAGE_HOSTS = List.of(
            "repo.maven.apache.org",
            "maven.repository.redhat.com",
            "registry.npmjs.org",
            "pypi.org",
            "files.pythonhosted.org");
    private static final List<String> SANDBOX_READ_ONLY_PATHS = List.of(
            "/bin",
            "/usr",
            "/lib",
            "/proc",
            "/dev/urandom",
            "/etc",
            "/var/log",
            "/home/tooling");
    private static final List<String> SANDBOX_READ_WRITE_PATHS = List.of(
            "/tmp",
            "/dev/null");
    private static final Map<String, String> SANDBOX_ENVIRONMENT = Map.of(
            "MAVEN_USER_HOME", "/sandbox/.m2",
            "MAVEN_OPTS", "-Dmaven.repo.local=/sandbox/.m2/repository",
            "GRADLE_USER_HOME", "/sandbox/.gradle",
            "npm_config_cache", "/sandbox/.npm",
            "GIT_CONFIG_GLOBAL", "/sandbox/.gitconfig");

    // Login profiles can put the UDI's default JDK back on PATH.
    private static final String EXEC_ENVIRONMENT = """
            if [ -n "${JAVA_HOME_21:-}" ] && [ -x "$JAVA_HOME_21/bin/javac" ]; then
                JAVA_HOME=$JAVA_HOME_21
            else
                for jdk in /home/tooling/.sdkman/candidates/java/21.*; do
                    if [ -x "$jdk/bin/javac" ]; then
                        JAVA_HOME=$jdk
                        break
                    fi
                done
            fi
            if [ -z "${MAVEN_HOME:-}" ] && [ -x /home/tooling/.sdkman/candidates/maven/current/bin/mvn ]; then
                MAVEN_HOME=/home/tooling/.sdkman/candidates/maven/current
            fi
            if [ -n "${MAVEN_HOME:-}" ]; then PATH="$MAVEN_HOME/bin:$PATH"; fi
            if [ -z "${JAVA_HOME:-}" ] && command -v javac >/dev/null; then
                javac_path=$(readlink -f "$(command -v javac)")
                JAVA_HOME=${javac_path%/bin/javac}
            fi
            if [ -n "${JAVA_HOME:-}" ]; then PATH="$JAVA_HOME/bin:$PATH"; fi
            export JAVA_HOME PATH

            # OpenShell supplies its proxy and CA as shell variables; Maven uses JVM properties.
            case "${HTTPS_PROXY:-}" in
                http://*:*)
                    tack_proxy=${HTTPS_PROXY#http://}
                    tack_proxy_host=${tack_proxy%:*}
                    tack_proxy_port=${tack_proxy##*:}
                    MAVEN_OPTS="$MAVEN_OPTS -Daether.connector.http.useSystemProperties=true"
                    MAVEN_OPTS="$MAVEN_OPTS -Dhttps.proxyHost=$tack_proxy_host -Dhttps.proxyPort=$tack_proxy_port"
                    MAVEN_OPTS="$MAVEN_OPTS -Dhttp.proxyHost=$tack_proxy_host -Dhttp.proxyPort=$tack_proxy_port"
                    ;;
            esac
            if [ -r /sandbox/.m2/tack-cacerts ]; then
                MAVEN_OPTS="$MAVEN_OPTS -Djavax.net.ssl.trustStore=/sandbox/.m2/tack-cacerts"
            fi
            export MAVEN_OPTS
            """;

    private static final String PREPARE_MAVEN = """
            case "$(javac -version 2>&1)" in
                "javac 21"|"javac 21."*) ;;
                *) echo "Java 21 javac is required in the sandbox" >&2; exit 1 ;;
            esac
            command -v mvn >/dev/null || { echo "Maven is required in the sandbox" >&2; exit 1; }
            ca=${NODE_EXTRA_CA_CERTS:-/etc/openshell-tls/openshell-ca.pem}
            if [ -r "$ca" ]; then
                truststore=/sandbox/.m2/tack-cacerts
                jdk_store="$JAVA_HOME/lib/security/cacerts"
                if [ ! -r "$truststore" ] || [ "$ca" -nt "$truststore" ] || [ "$jdk_store" -nt "$truststore" ]; then
                    mkdir -p /sandbox/.m2 || exit 1
                    temp_store=$(mktemp /sandbox/.m2/tack-cacerts.XXXXXX) || exit 1
                    trap 'rm -f "$temp_store"' 0
                    cp "$jdk_store" "$temp_store" &&
                        "$JAVA_HOME/bin/keytool" -importcert -noprompt -alias tack-openshell -file "$ca" \
                            -keystore "$temp_store" -storepass changeit &&
                        mv -f "$temp_store" "$truststore" || exit 1
                fi
            fi
            """;

    @GrpcClient("openshell")
    OpenShellGrpc.OpenShellBlockingStub stub;

    private final boolean enabled;
    private final Duration controlTimeout;
    private final Duration provisionTimeout;
    private final int execTimeoutSeconds;
    private final String sandboxImage;

    public OpenShellGrpcClient(
            @ConfigProperty(name = "agent.sandbox.openshell.enabled", defaultValue = "false") boolean enabled,
            @ConfigProperty(name = "agent.sandbox.openshell.control-timeout-seconds", defaultValue = "180") long controlTimeoutSeconds,
            @ConfigProperty(name = "agent.sandbox.openshell.provision-timeout-seconds", defaultValue = "900") long provisionTimeoutSeconds,
            @ConfigProperty(name = "agent.sandbox.openshell.exec-timeout-seconds", defaultValue = "120") int execTimeoutSeconds,
            @ConfigProperty(name = "agent.sandbox.openshell.image") String sandboxImage) {
        this.enabled = enabled;
        this.controlTimeout = Duration.ofSeconds(controlTimeoutSeconds);
        this.provisionTimeout = Duration.ofSeconds(provisionTimeoutSeconds);
        this.execTimeoutSeconds = execTimeoutSeconds;
        if (sandboxImage == null || sandboxImage.isBlank()) {
            throw new IllegalArgumentException("OpenShell sandbox image must be configured");
        }
        this.sandboxImage = sandboxImage.strip();
    }

    public boolean enabled() {
        return enabled;
    }

    Duration controlTimeout() {
        return controlTimeout;
    }

    Duration provisionTimeout() {
        return provisionTimeout;
    }

    Duration execTimeout() {
        return Duration.ofSeconds(execTimeoutSeconds);
    }

    public Optional<Sandbox> getSandbox(String name) {
        try {
            var response = controlStub().getSandbox(GetSandboxRequest.newBuilder()
                    .setName(name)
                    .setWorkspace(WORKSPACE)
                    .build());
            return response.hasSandbox() ? Optional.of(response.getSandbox()) : Optional.empty();
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
                return Optional.empty();
            }
            throw failure("get sandbox " + name, e);
        }
    }

    public Sandbox createSandbox(String name, List<String> providers, Map<String, String> labels) {
        SandboxPolicy policy = SandboxPolicy.newBuilder()
                .setFilesystem(FilesystemPolicy.newBuilder()
                        .setIncludeWorkdir(true)
                        .addAllReadOnly(SANDBOX_READ_ONLY_PATHS)
                        .addAllReadWrite(SANDBOX_READ_WRITE_PATHS)
                        .build())
                .setLandlock(LandlockPolicy.newBuilder().setCompatibility("best_effort").build())
                .build();
        SandboxSpec spec = SandboxSpec.newBuilder()
                .putAllEnvironment(SANDBOX_ENVIRONMENT)
                .setTemplate(SandboxTemplate.newBuilder().setImage(sandboxImage).build())
                .setPolicy(policy)
                .addAllProviders(providers == null ? List.of() : providers)
                .build();
        Sandbox created;
        try {
            created = controlStub().createSandbox(CreateSandboxRequest.newBuilder()
                    .setName(name)
                    .setWorkspace(WORKSPACE)
                    .setSpec(spec)
                    .putAllLabels(labels == null ? Map.of() : labels)
                    .build()).getSandbox();
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.ALREADY_EXISTS) {
                return getSandbox(name).orElseThrow(() -> failure("recover sandbox " + name, e));
            }
            throw failure("create sandbox " + name, e);
        }
        if (created == null || created.getMetadata().getId().isBlank()) {
            throw new IllegalStateException("OpenShell returned a sandbox without an id for " + name);
        }
        return created;
    }

    public Sandbox requireReady(String name) {
        Sandbox sandbox = getSandbox(name)
                .orElseThrow(() -> new IllegalStateException("OpenShell sandbox does not exist: " + name));
        if (sandbox.getStatus().getPhase() != SandboxPhase.SANDBOX_PHASE_READY) {
            throw new IllegalStateException("OpenShell sandbox is not ready: " + name
                    + " phase=" + sandbox.getStatus().getPhase());
        }
        return sandbox;
    }

    public void prepareMaven(String sandboxName) {
        SandboxExecResult result = exec(sandboxName, PREPARE_MAVEN, "/sandbox");
        if (result.exitCode() != 0) {
            throw new IllegalStateException("Unable to prepare Maven in sandbox " + sandboxName
                    + " exit=" + result.exitCode() + ": " + result.stderr());
        }
    }

    public SandboxExecResult exec(String sandboxName, String command, String workdir) {
        Sandbox sandbox = requireReady(sandboxName);
        ExecSandboxRequest request = ExecSandboxRequest.newBuilder()
                .setSandboxId(sandbox.getMetadata().getId())
                .addAllCommand(List.of("/bin/sh", "-lc", EXEC_ENVIRONMENT + command))
                .putAllEnvironment(SANDBOX_ENVIRONMENT)
                .setWorkdir(workdir == null || workdir.isBlank() ? "/sandbox" : workdir)
                .setTimeoutSeconds(execTimeoutSeconds)
                .setTty(false)
                .build();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        Integer exitCode = null;
        try {
            Iterator<ExecSandboxEvent> events = stub.withDeadlineAfter(execTimeoutSeconds + 15L, TimeUnit.SECONDS)
                    .execSandbox(request);
            while (events.hasNext()) {
                ExecSandboxEvent event = events.next();
                switch (event.getPayloadCase()) {
                    case STDOUT -> write(stdout, event.getStdout().getData().toByteArray());
                    case STDERR -> write(stderr, event.getStderr().getData().toByteArray());
                    case EXIT -> exitCode = event.getExit().getExitCode();
                    case PAYLOAD_NOT_SET -> { }
                }
            }
        } catch (StatusRuntimeException e) {
            throw failure("exec in sandbox " + sandboxName, e);
        }
        if (exitCode == null) {
            throw new IllegalStateException("OpenShell exec ended without an exit status for " + sandboxName);
        }
        return new SandboxExecResult(
                exitCode,
                stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8));
    }

    public void ensureProviderProfile(String profileId, Endpoint endpoint) {
        try {
            ProviderProfile existing = controlStub().getProviderProfile(GetProviderProfileRequest.newBuilder()
                    .setId(profileId)
                    .setWorkspace(WORKSPACE)
                    .build()).getProfile();
            validateProfileEndpoint(profileId, existing, endpoint);
            return;
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() != Status.Code.NOT_FOUND) {
                throw failure("get provider profile " + profileId, e);
            }
        }

        ProviderProfile profile = ProviderProfile.newBuilder()
                .setId(profileId)
                .setDisplayName("Tack Gitea")
                .setDescription("Gitea source control identity supplied by a Tack persona")
                .setCategory(ProviderProfileCategory.PROVIDER_PROFILE_CATEGORY_SOURCE_CONTROL)
                .addCredentials(ProviderProfileCredential.newBuilder()
                        .setName("token")
                        .setDescription("Gitea access token")
                        .addEnvVars("GITEA_TOKEN")
                        .setRequired(true)
                        .setAuthStyle("basic")
                        .build())
                .addEndpoints(NetworkEndpoint.newBuilder()
                        .setHost(endpoint.host())
                        .setPort(endpoint.port())
                        .addPorts(endpoint.port())
                        .setPath("/**")
                        .setProtocol("rest")
                        .setEnforcement("enforce")
                        .setAccess("full")
                        .build())
                .addBinaries(NetworkBinary.newBuilder().setPath("/usr/bin/git").build())
                .setDiscovery(ProviderProfileDiscovery.newBuilder().addCredentials("token").build())
                .build();
        try {
            var response = controlStub().importProviderProfiles(ImportProviderProfilesRequest.newBuilder()
                    .setWorkspace(WORKSPACE)
                    .addProfiles(ProviderProfileImportItem.newBuilder()
                            .setProfile(profile)
                            .setSource("tack")
                            .build())
                    .build());
            if (!response.getImported()) {
                String diagnostics = response.getDiagnosticsList().stream()
                        .map(d -> d.getField() + ": " + d.getMessage())
                        .reduce((a, b) -> a + "; " + b)
                        .orElse("unknown validation error");
                throw new IllegalStateException("OpenShell rejected provider profile " + profileId + ": " + diagnostics);
            }
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.ALREADY_EXISTS) {
                ProviderProfile existing = controlStub().getProviderProfile(GetProviderProfileRequest.newBuilder()
                        .setId(profileId)
                        .setWorkspace(WORKSPACE)
                        .build()).getProfile();
                validateProfileEndpoint(profileId, existing, endpoint);
                return;
            }
            throw failure("import provider profile " + profileId, e);
        }
    }

    public String ensureProvider(String name, String profileId, String token) {
        Optional<Provider> current = getProvider(name);
        Provider provider;
        if (current.isPresent()) {
            Provider existing = current.get();
            provider = existing.toBuilder()
                    .setType(profileId)
                    .setProfileWorkspace(WORKSPACE)
                    .putCredentials("GITEA_TOKEN", token)
                    .build();
            try {
                controlStub().updateProvider(UpdateProviderRequest.newBuilder()
                        .setProvider(provider)
                        .setWorkspace(WORKSPACE)
                        .build());
            } catch (StatusRuntimeException e) {
                throw failure("update provider " + name, e);
            }
        } else {
            provider = Provider.newBuilder()
                    .setMetadata(ObjectMeta.newBuilder().setName(name).setWorkspace(WORKSPACE).build())
                    .setType(profileId)
                    .setProfileWorkspace(WORKSPACE)
                    .putCredentials("GITEA_TOKEN", token)
                    .build();
            try {
                controlStub().createProvider(CreateProviderRequest.newBuilder()
                        .setProvider(provider)
                        .setWorkspace(WORKSPACE)
                        .build());
            } catch (StatusRuntimeException e) {
                if (e.getStatus().getCode() != Status.Code.ALREADY_EXISTS) {
                    throw failure("create provider " + name, e);
                }
            }
        }
        return name;
    }

    public void attachProvider(String sandboxName, String providerName) {
        try {
            controlStub().attachSandboxProvider(AttachSandboxProviderRequest.newBuilder()
                    .setSandboxName(sandboxName)
                    .setProviderName(providerName)
                    .setWorkspace(WORKSPACE)
                    .build());
        } catch (StatusRuntimeException e) {
            throw failure("attach provider " + providerName + " to sandbox " + sandboxName, e);
        }
    }

    public void authorizeDeveloperPackages(String sandboxName) {
        NetworkPolicyRule.Builder rule = NetworkPolicyRule.newBuilder()
                .setName("tack-developer-packages")
                .addBinaries(NetworkBinary.newBuilder().setPath("/**").build());
        for (String host : DEVELOPER_PACKAGE_HOSTS) {
            rule.addEndpoints(NetworkEndpoint.newBuilder()
                    .setHost(host)
                    .setPort(443)
                    .addPorts(443)
                    .setProtocol("rest")
                    .setEnforcement("enforce")
                    .setAccess("full")
                    .setAllowEncodedSlash("registry.npmjs.org".equals(host))
                    .build());
        }
        applyNetworkRule(sandboxName, rule.build(), "developer package");
    }

    public void authorizeGit(String sandboxName, Endpoint endpoint) {
        NetworkPolicyRule rule = NetworkPolicyRule.newBuilder()
                .setName("tack-gitea")
                .addEndpoints(NetworkEndpoint.newBuilder()
                        .setHost(endpoint.host())
                        .setPort(endpoint.port())
                        .addPorts(endpoint.port())
                        .setProtocol("rest")
                        .setEnforcement("enforce")
                        .setAccess("full")
                        .build())
                .addBinaries(NetworkBinary.newBuilder().setPath("/usr/bin/git").build())
                .build();
        applyNetworkRule(sandboxName, rule, "git");
    }

    private void applyNetworkRule(String sandboxName, NetworkPolicyRule rule, String egress) {
        int version;
        try {
            version = controlStub().updateConfig(UpdateConfigRequest.newBuilder()
                    .setName(sandboxName)
                    .setWorkspace(WORKSPACE)
                    .addMergeOperations(PolicyMergeOperation.newBuilder()
                            .setAddRule(AddNetworkRule.newBuilder()
                                    .setRuleName(rule.getName())
                                    .setRule(rule)
                                    .build())
                            .build())
                    .build()).getVersion();
        } catch (StatusRuntimeException e) {
            throw failure("authorize " + egress + " egress for sandbox " + sandboxName, e);
        }
        awaitPolicyLoaded(sandboxName, version);
    }

    private Optional<Provider> getProvider(String name) {
        try {
            return Optional.ofNullable(controlStub().getProvider(GetProviderRequest.newBuilder()
                    .setName(name)
                    .setWorkspace(WORKSPACE)
                    .build()).getProvider());
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
                return Optional.empty();
            }
            throw failure("get provider " + name, e);
        }
    }

    public Sandbox awaitProvisionedReady(Sandbox sandbox) {
        Deadline deadline = Deadline.after(provisionTimeout.toNanos(), TimeUnit.NANOSECONDS);
        Sandbox latest = sandbox;
        if (latest.getMetadata().getId().isBlank()) {
            throw new IllegalStateException("OpenShell sandbox has no id: " + describe(latest));
        }
        if (isReady(latest)) {
            return latest;
        }

        var request = WatchSandboxRequest.newBuilder()
                .setId(sandbox.getMetadata().getId())
                .setFollowStatus(true)
                .setStopOnTerminal(true)
                .build();
        while (!deadline.isExpired()) {
            // Cancel a stream we stop consuming as soon as we have the state we need.
            try (Context.CancellableContext watch = Context.current().withCancellation()) {
                Context parent = watch.attach();
                try {
                    Iterator<SandboxStreamEvent> events = stub.withDeadline(deadline).watchSandbox(request);
                    while (events.hasNext()) {
                        SandboxStreamEvent event = events.next();
                        if (event.hasSandbox()) {
                            latest = event.getSandbox();
                            if (isReady(latest)) {
                                return latest;
                            }
                        }
                    }
                } finally {
                    watch.detach(parent);
                }
            } catch (StatusRuntimeException e) {
                if (e.getStatus().getCode() == Status.Code.DEADLINE_EXCEEDED) {
                    throw readinessTimeout(latest, e);
                }
                if (e.getStatus().getCode() != Status.Code.UNAVAILABLE) {
                    throw failure("watch sandbox " + describe(latest), e);
                }
            }
            // A reconnect receives a fresh snapshot from the gateway.
            try {
                TimeUnit.NANOSECONDS.sleep(Math.min(
                        TimeUnit.MILLISECONDS.toNanos(100), deadline.timeRemaining(TimeUnit.NANOSECONDS)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted waiting for " + describe(latest), e);
            }
        }
        throw readinessTimeout(latest, null);
    }

    private static boolean isReady(Sandbox sandbox) {
        return switch (sandbox.getStatus().getPhase()) {
            case SANDBOX_PHASE_READY -> true;
            case SANDBOX_PHASE_PROVISIONING, SANDBOX_PHASE_STARTING,
                 SANDBOX_PHASE_UNSPECIFIED, SANDBOX_PHASE_UNKNOWN -> false;
            default -> throw new IllegalStateException("Sandbox cannot become ready: " + describe(sandbox));
        };
    }

    private static IllegalStateException readinessTimeout(Sandbox latest, Throwable cause) {
        return new IllegalStateException("Timed out waiting for OpenShell readiness: " + describe(latest), cause);
    }

    private void awaitPolicyLoaded(String sandboxName, int version) {
        Deadline deadline = Deadline.after(controlTimeout.toNanos(), TimeUnit.NANOSECONDS);
        while (!deadline.isExpired()) {
            try {
                var response = stub.withDeadline(deadline).getSandboxPolicyStatus(GetSandboxPolicyStatusRequest.newBuilder()
                        .setName(sandboxName)
                        .setVersion(version)
                        .setWorkspace(WORKSPACE)
                        .build());
                PolicyStatus status = response.getRevision().getStatus();
                if (status == PolicyStatus.POLICY_STATUS_FAILED) {
                    throw new IllegalStateException("OpenShell failed to load policy version " + version
                            + " for " + sandboxName + ": " + response.getRevision().getLoadError());
                }
                if (status == PolicyStatus.POLICY_STATUS_LOADED || response.getActiveVersion() >= version) {
                    return;
                }
                Thread.sleep(100);
            } catch (StatusRuntimeException e) {
                throw failure("check policy status for sandbox " + sandboxName, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted waiting for OpenShell policy load", e);
            }
        }
        throw new IllegalStateException("Timed out waiting for OpenShell policy version " + version
                + " to load for sandbox " + sandboxName);
    }

    OpenShellGrpc.OpenShellBlockingStub controlStub() {
        return stub.withDeadlineAfter(controlTimeout.toSeconds(), TimeUnit.SECONDS);
    }

    private static void validateProfileEndpoint(String profileId, ProviderProfile profile, Endpoint expected) {
        boolean matches = profile != null && profile.getEndpointsList().stream()
                .anyMatch(endpoint -> endpoint.getHost().equalsIgnoreCase(expected.host())
                        && (endpoint.getPort() == expected.port() || endpoint.getPortsList().contains(expected.port())));
        if (!matches) {
            throw new IllegalStateException("OpenShell provider profile " + profileId
                    + " already exists but does not authorize " + expected.host() + ":" + expected.port());
        }
    }

    static String describe(Sandbox sandbox) {
        if (sandbox == null) {
            return "phase=unknown";
        }
        String conditions = sandbox.getStatus().getConditionsList().stream()
                .map(condition -> condition.getType() + "=" + condition.getStatus()
                        + " " + condition.getReason() + " " + condition.getMessage())
                .reduce((first, next) -> first + ", " + next)
                .orElse("none");
        return "phase=" + sandbox.getStatus().getPhase() + " conditions=" + conditions;
    }

    private static void write(ByteArrayOutputStream stream, byte[] value) {
        stream.writeBytes(value);
    }

    private static IllegalStateException failure(String operation, StatusRuntimeException e) {
        return new IllegalStateException("OpenShell failed to " + operation + ": " + e.getStatus(), e);
    }

    public record Endpoint(String host, int port) {
    }

    public record SandboxExecResult(int exitCode, String stdout, String stderr) {
    }
}
