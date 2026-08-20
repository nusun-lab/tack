package us.nusun.llmshare.framework.sandbox;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class OpenShellCliClient {
    private final boolean enabled;
    private final String executable;
    private final String gatewayEndpoint;
    private final Duration controlTimeout;
    private final int execTimeoutSeconds;

    public OpenShellCliClient(
            @ConfigProperty(name = "agent.sandbox.openshell.enabled", defaultValue = "false") boolean enabled,
            @ConfigProperty(name = "agent.sandbox.openshell.executable", defaultValue = "openshell") String executable,
            @ConfigProperty(name = "agent.sandbox.openshell.gateway-endpoint", defaultValue = "http://openshell.tack.svc.cluster.local:8080") String gatewayEndpoint,
            @ConfigProperty(name = "agent.sandbox.openshell.control-timeout-seconds", defaultValue = "180") long controlTimeoutSeconds,
            @ConfigProperty(name = "agent.sandbox.openshell.exec-timeout-seconds", defaultValue = "120") int execTimeoutSeconds) {
        this.enabled = enabled;
        this.executable = executable;
        this.gatewayEndpoint = gatewayEndpoint;
        this.controlTimeout = Duration.ofSeconds(controlTimeoutSeconds);
        this.execTimeoutSeconds = execTimeoutSeconds;
    }

    public boolean enabled() {
        return enabled;
    }

    public CommandResult command(List<String> arguments) {
        return command(arguments, Map.of(), controlTimeout);
    }

    public CommandResult command(List<String> arguments, Map<String, String> environment) {
        return command(arguments, environment, controlTimeout);
    }

    public SandboxExecResult exec(String sandboxName, String command, String workdir) {
        List<String> arguments = new ArrayList<>();
        arguments.add("sandbox");
        arguments.add("exec");
        arguments.add("-n");
        arguments.add(sandboxName);
        arguments.add("--no-tty");
        if (workdir != null && !workdir.isBlank()) {
            arguments.add("--workdir");
            arguments.add(workdir);
        }
        arguments.add("--timeout");
        arguments.add(String.valueOf(execTimeoutSeconds));
        arguments.add("--");
        arguments.add("/bin/sh");
        arguments.add("-lc");
        arguments.add(command);
        CommandResult result = command(arguments, Map.of(), Duration.ofSeconds(execTimeoutSeconds + 15L));
        return new SandboxExecResult(result.exitCode(), result.stdout(), result.stderr());
    }

    private CommandResult command(List<String> arguments, Map<String, String> environment, Duration timeout) {
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.add("--gateway-endpoint");
        command.add(gatewayEndpoint);
        command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().putAll(environment);
        builder.environment().put("OPENSHELL_GATEWAY_ENDPOINT", gatewayEndpoint);
        try {
            Process process = builder.start();
            CompletableFuture<String> stdout = CompletableFuture.supplyAsync(() -> read(process.getInputStream()));
            CompletableFuture<String> stderr = CompletableFuture.supplyAsync(() -> read(process.getErrorStream()));
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("OpenShell command timed out: " + String.join(" ", arguments));
            }
            return new CommandResult(process.exitValue(), stdout.join(), stderr.join());
        } catch (IOException e) {
            throw new IllegalStateException("Unable to execute OpenShell CLI", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for OpenShell CLI", e);
        }
    }

    private static String read(java.io.InputStream input) {
        try (input) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read OpenShell command output", e);
        }
    }

    public record CommandResult(int exitCode, String stdout, String stderr) {
        public boolean successful() {
            return exitCode == 0;
        }

        public String diagnostic() {
            String value = stderr == null || stderr.isBlank() ? stdout : stderr;
            return value == null ? "" : value.strip();
        }
    }

    public record SandboxExecResult(int exitCode, String stdout, String stderr) {
    }
}
