package us.nusun.tack.framework.mcp;

import dev.langchain4j.mcp.client.McpClient;

import java.util.function.Supplier;

final class CachedMcpConnection implements AutoCloseable {
    private final Supplier<McpClient> connect;
    private McpClient client;
    private boolean closed;

    CachedMcpConnection(Supplier<McpClient> connect) {
        this.connect = connect;
    }

    synchronized McpClient client() {
        if (closed) {
            throw new IllegalStateException("MCP connection is closed");
        }
        if (client == null) {
            client = connect.get();
        }
        return client;
    }

    @Override
    public synchronized void close() throws Exception {
        if (!closed) {
            closed = true;
            McpClient opened = client;
            client = null;
            if (opened != null) {
                opened.close();
            }
        }
    }
}
