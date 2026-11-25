package us.nusun.tack.framework.sandbox;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import us.nusun.tack.framework.logging.LogTokens;
import us.nusun.tack.framework.persona.Persona;

public final class SandboxExecTool {
    private static final int MAX_LOG_VALUE_LENGTH = 4000;

    private final OpenShellGrpcClient openshell;
    private final String sandboxName;
    private final Persona persona;
    private final LogTokens logTokens;

    SandboxExecTool(OpenShellGrpcClient openshell, String sandboxName, Persona persona, LogTokens logTokens) {
        this.openshell = openshell;
        this.sandboxName = sandboxName;
        this.persona = persona;
        this.logTokens = logTokens;
    }

    @Tool("Execute a shell command in this persona's persistent OpenShell sandbox. Use it for git operations, repository inspection and edits, builds, tests, and other development work. The sandbox persists across AgentRuns for this persona within the mission.")
    public OpenShellGrpcClient.SandboxExecResult sandbox_exec(
            @P("Shell command to execute. The command runs through /bin/sh -lc inside the sandbox.") String command,
            @P("Working directory inside the sandbox. Use /sandbox until a repository has been cloned, then use the repository directory.") String workdir) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("sandbox_exec requires a command");
        }
        String effectiveWorkdir = workdir == null || workdir.isBlank() ? "/sandbox" : workdir;
        logDim("TOOL sandbox_exec REQUEST command=" + abbreviate(command) + " workdir=" + abbreviate(effectiveWorkdir));
        try {
            OpenShellGrpcClient.SandboxExecResult result = openshell.exec(sandboxName, command, effectiveWorkdir);
            logDim("TOOL sandbox_exec RESPONSE exitCode=" + result.exitCode()
                    + " stdout=" + abbreviate(result.stdout())
                    + " stderr=" + abbreviate(result.stderr()));
            return result;
        } catch (RuntimeException e) {
            logDim("TOOL sandbox_exec ERROR " + abbreviate(e.getMessage()));
            throw e;
        }
    }

    private void logDim(String message) {
        if (persona == null || logTokens == null) {
            return;
        }
        logTokens.print(logTokens.wrapDim(label(), persona.color(), message));
    }

    private String label() {
        if (persona.label() != null && !persona.label().isBlank()) {
            return persona.label();
        }
        return persona.name();
    }

    private static String abbreviate(Object value) {
        if (value == null) {
            return "null";
        }
        String text = String.valueOf(value);
        return text.length() <= MAX_LOG_VALUE_LENGTH ? text : text.substring(0, MAX_LOG_VALUE_LENGTH) + "...(truncated)";
    }
}
