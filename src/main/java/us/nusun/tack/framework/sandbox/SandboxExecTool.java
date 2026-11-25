package us.nusun.tack.framework.sandbox;

import static us.nusun.tack.framework.logging.LogTokens.abbreviateToolValue;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import us.nusun.tack.framework.logging.LogTokens;
import us.nusun.tack.framework.persona.Persona;

public final class SandboxExecTool {

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
        logTokens.printTool(persona, "TOOL sandbox_exec REQUEST command=" + abbreviateToolValue(command) + " workdir=" + abbreviateToolValue(effectiveWorkdir));
        try {
            OpenShellGrpcClient.SandboxExecResult result = openshell.exec(sandboxName, command, effectiveWorkdir);
            logTokens.printTool(persona, "TOOL sandbox_exec RESPONSE exitCode=" + result.exitCode()
                    + " stdout=" + abbreviateToolValue(result.stdout())
                    + " stderr=" + abbreviateToolValue(result.stderr()));
            return result;
        } catch (RuntimeException e) {
            logTokens.printTool(persona, "TOOL sandbox_exec ERROR " + abbreviateToolValue(e.getMessage()));
            throw e;
        }
    }

}
