package us.nusun.llmshare.framework.sandbox;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

public final class SandboxExecTool {
    private final OpenShellGrpcClient openshell;
    private final String sandboxName;

    SandboxExecTool(OpenShellGrpcClient openshell, String sandboxName) {
        this.openshell = openshell;
        this.sandboxName = sandboxName;
    }

    @Tool("Execute a shell command in this persona's persistent OpenShell sandbox. Use it for git operations, repository inspection and edits, builds, tests, and other development work. The sandbox persists across AgentRuns for this persona within the mission.")
    public OpenShellGrpcClient.SandboxExecResult sandbox_exec(
            @P("Shell command to execute. The command runs through /bin/sh -lc inside the sandbox.") String command,
            @P("Working directory inside the sandbox. Use /sandbox until a repository has been cloned, then use the repository directory.") String workdir) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("sandbox_exec requires a command");
        }
        String effectiveWorkdir = workdir == null || workdir.isBlank() ? "/sandbox" : workdir;
        return openshell.exec(sandboxName, command, effectiveWorkdir);
    }
}
