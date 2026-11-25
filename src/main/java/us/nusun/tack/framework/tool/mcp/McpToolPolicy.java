package us.nusun.tack.framework.tool.mcp;

import static us.nusun.tack.framework.logging.LogTokens.abbreviateToolValue;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderResult;
import us.nusun.tack.framework.logging.LogTokens;
import us.nusun.tack.framework.persona.Persona;
import us.nusun.tack.framework.tool.ToolProfile;

final class McpToolPolicy {
    private final String server;
    private final ToolProfile profile;
    private final Persona persona;
    private final LogTokens logTokens;

    private McpToolPolicy(String server, ToolProfile profile, Persona persona, LogTokens logTokens) {
        this.server = server;
        this.profile = profile;
        this.persona = persona;
        this.logTokens = logTokens;
    }

    static ToolProvider provider(McpClient client, String server, ToolProfile profile, Persona persona, LogTokens logTokens) {
        var policy = new McpToolPolicy(server, profile, persona, logTokens);
        boolean multipleServers = profile.policies().values().stream()
                .filter(entry -> entry.connection() != null).limit(2).count() == 2;
        String prefix = multipleServers ? server.replaceAll("[^A-Za-z0-9_]", "_") + "__" : "";
        var builder = McpToolProvider.builder()
                .mcpClients(client)
                .filter((delegate, tool) -> profile.allowsTool(server, tool.name()));
        if (multipleServers) {
            builder.toolNameMapper((delegate, tool) -> prefix + tool.name());
        }
        var provider = builder.build();

        // Bind the execution check to the same tool as the native executor.
        return request -> new ToolProviderResult(provider.provideTools(request).aiServiceTools().stream()
                .map(tool -> tool.toBuilder()
                        .toolExecutor(policy.wrap(tool.name(), tool.name().substring(prefix.length()), tool.toolExecutor()))
                        .build())
                .toList());
    }

    private ToolExecutor wrap(String exposedName, String toolName, ToolExecutor executor) {
        return new ToolExecutor() {
            @Override
            public String execute(ToolExecutionRequest request, Object memoryId) {
                return executeWithContext(request, InvocationContext.builder().chatMemoryId(memoryId).build()).resultText();
            }

            @Override
            public ToolExecutionResult executeWithContext(ToolExecutionRequest request, InvocationContext context) {
                if (!exposedName.equals(request.name())) {
                    throw new IllegalArgumentException("MCP executor for " + exposedName + " cannot execute " + request.name());
                }
                profile.checkExecution(server, toolName, request.arguments());
                String tool = "TOOL " + server + "." + toolName;
                log(tool + " REQUEST " + abbreviateToolValue(request.arguments()));
                try {
                    ToolExecutionResult result = executor.executeWithContext(request, context);
                    log(tool + " RESPONSE isError=" + result.isError()
                            + " " + abbreviateToolValue(result.resultContents()));
                    return result;
                } catch (RuntimeException failure) {
                    log(tool + " ERROR " + abbreviateToolValue(failure.getMessage()));
                    throw failure;
                }
            }
        };
    }

    private void log(String message) {
        if (logTokens != null) {
            logTokens.printTool(persona, message);
        }
    }
}
