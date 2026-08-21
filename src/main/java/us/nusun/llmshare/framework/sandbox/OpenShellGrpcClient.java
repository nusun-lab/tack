package us.nusun.llmshare.framework.sandbox;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.quarkus.grpc.GrpcClient;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import us.nusun.llmshare.framework.sandbox.grpc.AddNetworkRule;
import us.nusun.llmshare.framework.sandbox.grpc.AttachSandboxProviderRequest;
import us.nusun.llmshare.framework.sandbox.grpc.CreateProviderRequest;
import us.nusun.llmshare.framework.sandbox.grpc.CreateSandboxRequest;
import us.nusun.llmshare.framework.sandbox.grpc.ExecSandboxEvent;
import us.nusun.llmshare.framework.sandbox.grpc.ExecSandboxRequest;
import us.nusun.llmshare.framework.sandbox.grpc.GetProviderProfileRequest;
import us.nusun.llmshare.framework.sandbox.grpc.GetProviderRequest;
import us.nusun.llmshare.framework.sandbox.grpc.GetSandboxPolicyStatusRequest;
import us.nusun.llmshare.framework.sandbox.grpc.GetSandboxRequest;
import us.nusun.llmshare.framework.sandbox.grpc.ImportProviderProfilesRequest;
import us.nusun.llmshare.framework.sandbox.grpc.NetworkBinary;
import us.nusun.llmshare.framework.sandbox.grpc.NetworkEndpoint;
import us.nusun.llmshare.framework.sandbox.grpc.NetworkPolicyRule;
import us.nusun.llmshare.framework.sandbox.grpc.ObjectMeta;
import us.nusun.llmshare.framework.sandbox.grpc.OpenShellGrpc;
import us.nusun.llmshare.framework.sandbox.grpc.PolicyMergeOperation;
import us.nusun.llmshare.framework.sandbox.grpc.PolicyStatus;
import us.nusun.llmshare.framework.sandbox.grpc.Provider;
import us.nusun.llmshare.framework.sandbox.grpc.ProviderProfile;
import us.nusun.llmshare.framework.sandbox.grpc.ProviderProfileCategory;
import us.nusun.llmshare.framework.sandbox.grpc.ProviderProfileCredential;
import us.nusun.llmshare.framework.sandbox.grpc.ProviderProfileDiscovery;
import us.nusun.llmshare.framework.sandbox.grpc.ProviderProfileImportItem;
import us.nusun.llmshare.framework.sandbox.grpc.Sandbox;
import us.nusun.llmshare.framework.sandbox.grpc.SandboxPhase;
import us.nusun.llmshare.framework.sandbox.grpc.SandboxSpec;
import us.nusun.llmshare.framework.sandbox.grpc.UpdateConfigRequest;
import us.nusun.llmshare.framework.sandbox.grpc.UpdateProviderRequest;
import us.nusun.llmshare.framework.sandbox.grpc.WatchSandboxRequest;

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
    private static final String WORKSPACE = "default";
    private static final List<String> DEVELOPER_PACKAGE_HOSTS = List.of(
            "repo.maven.apache.org",
            "maven.repository.redhat.com",
            "registry.npmjs.org",
            "pypi.org",
            "files.pythonhosted.org");

    @GrpcClient("openshell")
    OpenShellGrpc.OpenShellBlockingStub stub;

    private final boolean enabled;
    private final Duration controlTimeout;
    private final int execTimeoutSeconds;

    public OpenShellGrpcClient(
            @ConfigProperty(name = "agent.sandbox.openshell.enabled", defaultValue = "false") boolean enabled,
            @ConfigProperty(name = "agent.sandbox.openshell.control-timeout-seconds", defaultValue = "180") long controlTimeoutSeconds,
            @ConfigProperty(name = "agent.sandbox.openshell.exec-timeout-seconds", defaultValue = "120") int execTimeoutSeconds) {
        this.enabled = enabled;
        this.controlTimeout = Duration.ofSeconds(controlTimeoutSeconds);
        this.execTimeoutSeconds = execTimeoutSeconds;
    }

    public boolean enabled() {
        return enabled;
    }

    public Optional<Sandbox> getSandbox(String name) {
        try {
            return Optional.ofNullable(controlStub().getSandbox(GetSandboxRequest.newBuilder()
                    .setName(name)
                    .setWorkspace(WORKSPACE)
                    .build()).getSandbox());
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
                return Optional.empty();
            }
            throw failure("get sandbox " + name, e);
        }
    }

    public Sandbox createSandboxReady(String name, List<String> providers, Map<String, String> labels) {
        SandboxSpec spec = SandboxSpec.newBuilder()
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
            throw failure("create sandbox " + name, e);
        }
        if (created == null || created.getMetadata().getId().isBlank()) {
            throw new IllegalStateException("OpenShell returned a sandbox without an id for " + name);
        }
        Sandbox ready = awaitProvisionedReady(created);
        authorizeDeveloperPackages(name);
        return ready;
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

    public SandboxExecResult exec(String sandboxName, String command, String workdir) {
        Sandbox sandbox = requireReady(sandboxName);
        ExecSandboxRequest request = ExecSandboxRequest.newBuilder()
                .setSandboxId(sandbox.getMetadata().getId())
                .addAllCommand(List.of("/bin/sh", "-lc", command))
                .setWorkdir(workdir == null || workdir.isBlank() ? "/sandbox" : workdir)
                .setTimeoutSeconds(execTimeoutSeconds)
                .setTty(false)
                .build();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exitCode = 0;
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

    private void authorizeDeveloperPackages(String sandboxName) {
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
                    .build());
        }
        int version;
        try {
            version = controlStub().updateConfig(UpdateConfigRequest.newBuilder()
                    .setName(sandboxName)
                    .setWorkspace(WORKSPACE)
                    .addMergeOperations(PolicyMergeOperation.newBuilder()
                            .setAddRule(AddNetworkRule.newBuilder()
                                    .setRuleName("tack-developer-packages")
                                    .setRule(rule.build())
                                    .build())
                            .build())
                    .build()).getVersion();
        } catch (StatusRuntimeException e) {
            throw failure("authorize developer package egress for sandbox " + sandboxName, e);
        }
        awaitPolicyLoaded(sandboxName, version);
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
        int version;
        try {
            version = controlStub().updateConfig(UpdateConfigRequest.newBuilder()
                    .setName(sandboxName)
                    .setWorkspace(WORKSPACE)
                    .addMergeOperations(PolicyMergeOperation.newBuilder()
                            .setAddRule(AddNetworkRule.newBuilder()
                                    .setRuleName("tack-gitea")
                                    .setRule(rule)
                                    .build())
                            .build())
                    .build()).getVersion();
        } catch (StatusRuntimeException e) {
            throw failure("authorize git egress for sandbox " + sandboxName, e);
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

    private Sandbox awaitProvisionedReady(Sandbox created) {
        boolean sawNonReady = created.getStatus().getPhase() != SandboxPhase.SANDBOX_PHASE_READY;
        Sandbox latest = created;
        try {
            Iterator<us.nusun.llmshare.framework.sandbox.grpc.SandboxStreamEvent> events = controlStub()
                    .watchSandbox(WatchSandboxRequest.newBuilder()
                            .setId(created.getMetadata().getId())
                            .setFollowStatus(true)
                            .setFollowLogs(false)
                            .setFollowEvents(false)
                            .setStopOnTerminal(false)
                            .build());
            while (events.hasNext()) {
                var event = events.next();
                if (event.getPayloadCase() != us.nusun.llmshare.framework.sandbox.grpc.SandboxStreamEvent.PayloadCase.SANDBOX) {
                    continue;
                }
                latest = event.getSandbox();
                SandboxPhase phase = latest.getStatus().getPhase();
                if (phase == SandboxPhase.SANDBOX_PHASE_ERROR) {
                    throw new IllegalStateException("OpenShell sandbox entered error phase: " + conditionMessage(latest));
                }
                if (phase != SandboxPhase.SANDBOX_PHASE_READY) {
                    sawNonReady = true;
                }
                if (sawNonReady && phase == SandboxPhase.SANDBOX_PHASE_READY) {
                    return latest;
                }
            }
        } catch (StatusRuntimeException e) {
            throw failure("wait for sandbox " + created.getMetadata().getName(), e);
        }
        if (latest.getStatus().getPhase() == SandboxPhase.SANDBOX_PHASE_READY && sawNonReady) {
            return latest;
        }
        throw new IllegalStateException("OpenShell provisioning stream ended before sandbox became ready: "
                + created.getMetadata().getName());
    }

    private void awaitPolicyLoaded(String sandboxName, int version) {
        long deadline = System.nanoTime() + controlTimeout.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                var response = controlStub().getSandboxPolicyStatus(GetSandboxPolicyStatusRequest.newBuilder()
                        .setName(sandboxName)
                        .setVersion(version)
                        .setWorkspace(WORKSPACE)
                        .build());
                PolicyStatus status = response.getRevision().getStatus();
                if (status == PolicyStatus.POLICY_STATUS_LOADED || response.getActiveVersion() >= version) {
                    return;
                }
                if (status == PolicyStatus.POLICY_STATUS_FAILED) {
                    throw new IllegalStateException("OpenShell failed to load policy version " + version
                            + " for " + sandboxName + ": " + response.getRevision().getLoadError());
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

    private OpenShellGrpc.OpenShellBlockingStub controlStub() {
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

    private static String conditionMessage(Sandbox sandbox) {
        return sandbox.getStatus().getConditionsList().stream()
                .filter(condition -> "Ready".equals(condition.getType()))
                .map(condition -> condition.getReason() + ": " + condition.getMessage())
                .findFirst()
                .orElse("unknown error");
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
