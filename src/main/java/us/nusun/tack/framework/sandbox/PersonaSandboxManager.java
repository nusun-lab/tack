package us.nusun.tack.framework.sandbox;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import us.nusun.tack.framework.logging.LogTokens;
import us.nusun.tack.framework.persona.ExternalIdentity;
import us.nusun.tack.framework.run.Mission;
import us.nusun.tack.framework.persona.Persona;
import us.nusun.tack.framework.sandbox.grpc.Sandbox;
import us.nusun.tack.framework.run.AgentRun;

import java.net.URI;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class PersonaSandboxManager {
    public static final String WORKING_STATE_SANDBOX_NAME = "sandboxName";
    private static final Logger LOG = Logger.getLogger(PersonaSandboxManager.class);
    private static final String GITEA_PROFILE_ID = "tack-gitea";

    private final OpenShellGrpcClient openshell;
    private final OpenShellLifecycleClient lifecycle;
    private final LogTokens logTokens;
    private final String configuredGiteaUrl;
    private final ConcurrentHashMap<String, Persona> personasBySandbox = new ConcurrentHashMap<>();
    private final Object giteaProfileLock = new Object();
    private volatile String installedGiteaProfileEndpoint;

    public PersonaSandboxManager(
            OpenShellGrpcClient openshell,
            OpenShellLifecycleClient lifecycle,
            LogTokens logTokens,
            @ConfigProperty(name = "agent.sandbox.gitea.url", defaultValue = "") String configuredGiteaUrl) {
        this.openshell = openshell;
        this.lifecycle = lifecycle;
        this.logTokens = logTokens;
        this.configuredGiteaUrl = configuredGiteaUrl == null ? "" : configuredGiteaUrl.strip();
    }

    public SandboxRef ensureReady(Mission mission, Persona persona, AgentRun run) {
        if (!openshell.enabled()) {
            return null;
        }
        if (mission == null || persona == null || run == null) {
            throw new IllegalArgumentException("Mission, persona, and AgentRun are required for sandbox admission");
        }
        SandboxRef ref = provision(mission, persona, run);
        personasBySandbox.put(ref.name(), persona);
        run.workingState().put(WORKING_STATE_SANDBOX_NAME, ref.name());
        return ref;
    }

    public SandboxExecTool tool(String sandboxName) {
        if (!openshell.enabled() || sandboxName == null || sandboxName.isBlank()) {
            return null;
        }
        return new SandboxExecTool(openshell, sandboxName, personasBySandbox.get(sandboxName), logTokens);
    }

    public String sandboxNameFor(String missionId, String personaId) {
        if (blank(missionId) || blank(personaId)) {
            return null;
        }
        return sandboxName(missionId, personaId);
    }

    public List<String> destroyAll() {
        try {
            return lifecycle.deleteTackSandboxes();
        } finally {
            personasBySandbox.clear();
        }
    }

    private SandboxRef provision(Mission mission, Persona persona, AgentRun run) {
        String sandboxName = sandboxName(mission.id(), persona.id());
        String context = "mission=" + mission.id() + " persona=" + persona.id() + " sandbox=" + sandboxName;
        long preparationStarted = System.nanoTime();
        long stageStarted = preparationStarted;
        String stage = "sandbox lookup";
        Duration timeout = openshell.controlTimeout();
        Sandbox sandbox = null;
        try {
            sandbox = openshell.getSandbox(sandboxName).orElse(null);
            ExternalIdentity gitea = persona.identityFor("gitea");
            String giteaUrl = firstNonBlank(configuredGiteaUrl, repositoryBaseUrl(repositoryUrl(run)));
            String providerName = null;
            OpenShellGrpcClient.Endpoint endpoint = null;
            if (gitea != null && !blank(gitea.token()) && !blank(gitea.handle()) && !blank(giteaUrl)) {
                stage = "Gitea provider setup";
                stageStarted = System.nanoTime();
                endpoint = endpoint(giteaUrl);
                ensureGiteaProfile(endpoint);
                providerName = openshell.ensureProvider(providerName(persona), GITEA_PROFILE_ID, gitea.token());
            }

            if (sandbox == null) {
                stage = "sandbox creation";
                stageStarted = System.nanoTime();
                sandbox = openshell.createSandbox(
                        sandboxName,
                        providerName == null ? List.of() : List.of(providerName),
                        Map.of(
                                "tack-owner", "tack",
                                "tack-mission", shortHash(mission.id()),
                                "tack-persona", labelValue(persona.id())));
            }

            stage = "readiness";
            stageStarted = System.nanoTime();
            timeout = openshell.provisionTimeout();
            sandbox = openshell.awaitProvisionedReady(sandbox);
            LOG.infof("OpenShell sandbox ready %s elapsedMs=%d timeoutSeconds=%d %s",
                    context, elapsedMillis(stageStarted), timeout.toSeconds(), OpenShellGrpcClient.describe(sandbox));

            stage = "developer package policy";
            stageStarted = System.nanoTime();
            timeout = openshell.controlTimeout();
            openshell.authorizeDeveloperPackages(sandboxName);

            if (providerName != null) {
                if (!sandbox.getSpec().getProvidersList().contains(providerName)) {
                    stage = "Gitea provider attachment";
                    stageStarted = System.nanoTime();
                    openshell.attachProvider(sandboxName, providerName);
                }
                stage = "Git network policy";
                stageStarted = System.nanoTime();
                openshell.authorizeGit(sandboxName, endpoint);

                stage = "Git identity and credentials";
                stageStarted = System.nanoTime();
                timeout = openshell.execTimeout();
                configureGitIdentity(sandboxName, persona, gitea);
            }
            stage = "Java 21 Maven environment";
            stageStarted = System.nanoTime();
            timeout = openshell.execTimeout();
            openshell.prepareMaven(sandboxName);

            LOG.infof("OpenShell sandbox prepared %s elapsedMs=%d", context, elapsedMillis(preparationStarted));
            return new SandboxRef(sandboxName);
        } catch (RuntimeException e) {
            String message = "OpenShell sandbox preparation failed " + context + " stage=" + stage
                    + " elapsedMs=" + elapsedMillis(stageStarted) + " timeoutSeconds=" + timeout.toSeconds()
                    + " totalElapsedMs=" + elapsedMillis(preparationStarted) + " "
                    + (stage.equals("readiness") ? "" : OpenShellGrpcClient.describe(sandbox) + " ")
                    + e.getMessage();
            LOG.warn(message);
            throw new IllegalStateException(message, e);
        }
    }

    private static long elapsedMillis(long started) {
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }

    private void ensureGiteaProfile(OpenShellGrpcClient.Endpoint endpoint) {
        String endpointKey = endpoint.host() + ":" + endpoint.port();
        if (endpointKey.equals(installedGiteaProfileEndpoint)) {
            return;
        }
        synchronized (giteaProfileLock) {
            if (endpointKey.equals(installedGiteaProfileEndpoint)) {
                return;
            }
            openshell.ensureProviderProfile(GITEA_PROFILE_ID, endpoint);
            installedGiteaProfileEndpoint = endpointKey;
        }
    }

    private void configureGitIdentity(String sandboxName, Persona persona, ExternalIdentity identity) {
        String handle = identity.handle();
        String email = firstNonBlank(persona.email(), handle + "@tack.local");
        String helper = "!f() { if [ \"$1\" = get ]; then echo \"username=" + shellSafe(handle)
                + "\"; echo \"password=$GITEA_TOKEN\"; fi; }; f";
        String command = "git config --global user.name '" + shellSafe(handle) + "'"
                + " && git config --global user.email '" + shellSafe(email) + "'"
                + " && git config --global credential.helper '" + shellSafe(helper) + "'";
        OpenShellGrpcClient.SandboxExecResult result = openshell.exec(sandboxName, command, "/sandbox");
        if (result.exitCode() != 0) {
            throw new IllegalStateException("Unable to configure git identity in sandbox " + sandboxName + " exit=" + result.exitCode() + ": " + result.stderr());
        }
    }

    private static String providerName(Persona persona) {
        return "tack-gitea-" + labelValue(persona.id()) + "-" + shortHash(persona.id());
    }

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

    private static OpenShellGrpcClient.Endpoint endpoint(String url) {
        URI uri = URI.create(url);
        if (uri.getHost() == null) {
            throw new IllegalArgumentException("Gitea URL must contain a host: " + url);
        }
        int port = uri.getPort() > 0 ? uri.getPort() : ("http".equalsIgnoreCase(uri.getScheme()) ? 80 : 443);
        return new OpenShellGrpcClient.Endpoint(uri.getHost(), port);
    }

    private static String sandboxName(String missionId, String personaId) {
        return "tack-" + shortHash(missionId + "\n" + personaId);
    }

    private static String labelValue(String value) {
        String normalized = value == null ? "persona" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9.-]+", "-")
                .replaceAll("^-+|-+$", "");
        return normalized.isBlank() ? "persona" : normalized;
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(value).getBytes(java.nio.charset.StandardCharsets.UTF_8));
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

    public record SandboxRef(String name) {
    }

}
