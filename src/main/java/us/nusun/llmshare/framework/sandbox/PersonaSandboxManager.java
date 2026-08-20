package us.nusun.llmshare.framework.sandbox;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import us.nusun.llmshare.framework.model.ExternalIdentity;
import us.nusun.llmshare.framework.model.Mission;
import us.nusun.llmshare.framework.model.Persona;
import us.nusun.llmshare.framework.state.AgentRun;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class PersonaSandboxManager {
    public static final String WORKING_STATE_SANDBOX_NAME = "sandboxName";
    private static final String GITEA_PROFILE_ID = "tack-gitea";

    private final OpenShellCliClient openshell;
    private final String configuredGiteaUrl;
    private final ConcurrentHashMap<SandboxKey, CompletableFuture<SandboxRef>> sandboxes = new ConcurrentHashMap<>();
    private final Object giteaProfileLock = new Object();
    private volatile String installedGiteaProfileEndpoint;

    public PersonaSandboxManager(
            OpenShellCliClient openshell,
            @ConfigProperty(name = "agent.sandbox.gitea.url", defaultValue = "") String configuredGiteaUrl) {
        this.openshell = openshell;
        this.configuredGiteaUrl = configuredGiteaUrl == null ? "" : configuredGiteaUrl.strip();
    }

    public SandboxRef ensureReady(Mission mission, Persona persona, AgentRun run) {
        if (!openshell.enabled()) {
            return null;
        }
        if (mission == null || persona == null || run == null) {
            throw new IllegalArgumentException("Mission, persona, and AgentRun are required for sandbox admission");
        }
        SandboxKey key = new SandboxKey(mission.id(), persona.id());
        CompletableFuture<SandboxRef> mine = new CompletableFuture<>();
        CompletableFuture<SandboxRef> existing = sandboxes.putIfAbsent(key, mine);
        if (existing != null) {
            SandboxRef ref = existing.join();
            run.workingState().put(WORKING_STATE_SANDBOX_NAME, ref.name());
            return ref;
        }
        try {
            SandboxRef ref = provision(mission, persona, run);
            mine.complete(ref);
            run.workingState().put(WORKING_STATE_SANDBOX_NAME, ref.name());
            return ref;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            sandboxes.remove(key, mine);
            throw e;
        }
    }

    public SandboxExecTool tool(String sandboxName) {
        if (!openshell.enabled() || sandboxName == null || sandboxName.isBlank()) {
            return null;
        }
        return new SandboxExecTool(openshell, sandboxName);
    }

    private SandboxRef provision(Mission mission, Persona persona, AgentRun run) {
        String sandboxName = sandboxName(mission.id(), persona.id());
        ExternalIdentity gitea = persona.identityFor("gitea");
        String repositoryUrl = repositoryUrl(run);
        String giteaUrl = firstNonBlank(configuredGiteaUrl, repositoryBaseUrl(repositoryUrl));
        String providerName = null;
        Endpoint endpoint = null;
        if (gitea != null && !blank(gitea.token()) && !blank(gitea.handle()) && !blank(giteaUrl)) {
            endpoint = Endpoint.from(giteaUrl);
            ensureGiteaProfile(endpoint);
            providerName = ensureGiteaProvider(persona, gitea);
        }

        OpenShellCliClient.CommandResult get = openshell.command(List.of("sandbox", "get", sandboxName));
        if (!get.successful()) {
            List<String> create = new java.util.ArrayList<>(List.of(
                    "sandbox", "create",
                    "--name", sandboxName,
                    "--no-auto-providers",
                    "--no-tty",
                    "--label", "tack-mission=" + shortHash(mission.id()),
                    "--label", "tack-persona=" + labelValue(persona.id())));
            if (providerName != null) {
                create.add("--provider");
                create.add(providerName);
            }
            create.add("--");
            create.add("/bin/true");
            OpenShellCliClient.CommandResult created = openshell.command(create);
            requireSuccess(created, "create sandbox " + sandboxName);
        } else {
            requireReady(sandboxName);
            if (providerName != null) {
                OpenShellCliClient.CommandResult attach = openshell.command(List.of(
                        "sandbox", "provider", "attach", sandboxName, providerName));
                if (!attach.successful() && !attach.diagnostic().toLowerCase(Locale.ROOT).contains("already")) {
                    requireSuccess(attach, "attach Gitea provider to " + sandboxName);
                }
            }
        }

        if (providerName != null && endpoint != null) {
            authorizeGit(sandboxName, endpoint);
            configureGitIdentity(sandboxName, persona, gitea);
        }
        return new SandboxRef(sandboxName);
    }

    private void requireReady(String sandboxName) {
        OpenShellCliClient.SandboxExecResult result = openshell.exec(sandboxName, "/bin/true", "/sandbox");
        if (result.exitCode() != 0) {
            throw new IllegalStateException("Existing OpenShell sandbox is not ready: " + sandboxName + ": " + result.stderr());
        }
    }

    private void ensureGiteaProfile(Endpoint endpoint) {
        String endpointKey = endpoint.host() + ":" + endpoint.port();
        if (endpointKey.equals(installedGiteaProfileEndpoint)) {
            return;
        }
        synchronized (giteaProfileLock) {
            if (endpointKey.equals(installedGiteaProfileEndpoint)) {
                return;
            }
            OpenShellCliClient.CommandResult existing = openshell.command(List.of(
                    "provider", "profile", "export", GITEA_PROFILE_ID, "--output", "yaml"));
            if (!existing.successful()) {
                Path profile = null;
                try {
                    profile = Files.createTempFile("tack-gitea-provider-", ".yaml");
                    Files.writeString(profile, giteaProfile(endpoint));
                    requireSuccess(openshell.command(List.of(
                            "provider", "profile", "lint", "--file", profile.toString())), "lint Gitea provider profile");
                    requireSuccess(openshell.command(List.of(
                            "provider", "profile", "import", "--file", profile.toString())), "import Gitea provider profile");
                } catch (java.io.IOException e) {
                    throw new IllegalStateException("Unable to prepare Gitea provider profile", e);
                } finally {
                    if (profile != null) {
                        try { Files.deleteIfExists(profile); } catch (java.io.IOException ignored) { }
                    }
                }
            }
            installedGiteaProfileEndpoint = endpointKey;
        }
    }

    private String ensureGiteaProvider(Persona persona, ExternalIdentity identity) {
        String name = "tack-gitea-" + labelValue(persona.id()) + "-" + shortHash(persona.id());
        Map<String, String> secretEnvironment = Map.of("GITEA_TOKEN", identity.token());
        OpenShellCliClient.CommandResult existing = openshell.command(List.of("provider", "get", name));
        OpenShellCliClient.CommandResult result;
        if (existing.successful()) {
            result = openshell.command(List.of(
                    "provider", "update", name,
                    "--credential", "GITEA_TOKEN"), secretEnvironment);
        } else {
            result = openshell.command(List.of(
                    "provider", "create",
                    "--name", name,
                    "--type", GITEA_PROFILE_ID,
                    "--credential", "GITEA_TOKEN"), secretEnvironment);
        }
        requireSuccess(result, "ensure Gitea provider for persona " + persona.id());
        return name;
    }

    private void authorizeGit(String sandboxName, Endpoint endpoint) {
        String spec = endpoint.host() + ":" + endpoint.port() + ":full:rest:enforce";
        OpenShellCliClient.CommandResult result = openshell.command(List.of(
                "policy", "update", sandboxName,
                "--add-endpoint", spec,
                "--binary", "/usr/bin/git",
                "--rule-name", "tack-gitea",
                "--wait"));
        requireSuccess(result, "authorize git egress for " + sandboxName);
    }

    private void configureGitIdentity(String sandboxName, Persona persona, ExternalIdentity identity) {
        String handle = identity.handle();
        String email = handle + "@tack.local";
        String helper = "!f() { if [ \"$1\" = get ]; then echo \"username=" + shellSafe(handle)
                + "\"; echo \"password=$GITEA_TOKEN\"; fi; }; f";
        String command = "git config --global user.name '" + shellSafe(persona.name()) + "'"
                + " && git config --global user.email '" + shellSafe(email) + "'"
                + " && git config --global credential.helper '" + shellSafe(helper) + "'";
        OpenShellCliClient.SandboxExecResult result = openshell.exec(sandboxName, command, "/sandbox");
        if (result.exitCode() != 0) {
            throw new IllegalStateException("Unable to configure git identity in sandbox " + sandboxName + ": " + result.stderr());
        }
    }

    private static String giteaProfile(Endpoint endpoint) {
        return """
                id: tack-gitea
                display_name: Tack Gitea
                description: Gitea source control identity supplied by a Tack persona
                category: source_control
                inference_capable: false
                credentials:
                  - name: token
                    description: Gitea access token
                    env_vars: [GITEA_TOKEN]
                    required: true
                    auth_style: basic
                discovery:
                  credentials: [token]
                endpoints:
                  - host: %s
                    port: %d
                    path: /**
                    protocol: rest
                    enforcement: enforce
                    access: full
                binaries:
                  - /usr/bin/git
                """.formatted(endpoint.host(), endpoint.port());
    }

    @SuppressWarnings("unchecked")
    private static String repositoryUrl(AgentRun run) {
        Object input = run.input();
        if (!(input instanceof Map<?, ?> map)) {
            return null;
        }
        Object value = map.get("repositoryUrl");
        return value == null ? null : String.valueOf(value);
    }

    private static String repositoryBaseUrl(String repositoryUrl) {
        if (blank(repositoryUrl)) {
            return null;
        }
        try {
            URI uri = URI.create(repositoryUrl);
            if (uri.getHost() == null) {
                return null;
            }
            int port = uri.getPort();
            return uri.getScheme() + "://" + uri.getHost() + (port > 0 ? ":" + port : "");
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String sandboxName(String missionId, String personaId) {
        String persona = labelValue(personaId);
        if (persona.length() > 30) {
            persona = persona.substring(persona.length() - 30);
        }
        return "tack-" + shortHash(missionId) + "-" + persona;
    }

    private static String labelValue(String value) {
        String normalized = value == null ? "persona" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9.-]+", "-")
                .replaceAll("^-+|-+$", "");
        return normalized.isBlank() ? "persona" : normalized;
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(String.valueOf(value).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String shellSafe(String value) {
        return String.valueOf(value).replace("'", "'\"'\"'");
    }

    private static String firstNonBlank(String first, String second) {
        return !blank(first) ? first : (!blank(second) ? second : null);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static void requireSuccess(OpenShellCliClient.CommandResult result, String operation) {
        if (!result.successful()) {
            throw new IllegalStateException("OpenShell failed to " + operation + ": " + result.diagnostic());
        }
    }

    public record SandboxRef(String name) {
    }

    private record SandboxKey(String missionId, String personaId) {
    }

    private record Endpoint(String host, int port) {
        static Endpoint from(String url) {
            URI uri = URI.create(url);
            if (uri.getHost() == null) {
                throw new IllegalArgumentException("Gitea URL must contain a host: " + url);
            }
            int port = uri.getPort() > 0 ? uri.getPort() : ("http".equalsIgnoreCase(uri.getScheme()) ? 80 : 443);
            return new Endpoint(uri.getHost(), port);
        }
    }
}
