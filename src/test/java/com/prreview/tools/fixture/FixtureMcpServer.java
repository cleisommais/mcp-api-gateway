package com.prreview.tools.fixture;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.concurrent.CountDownLatch;

/** Child process of the client. stdout is reserved for MCP JSON-RPC messages. */
public final class FixtureMcpServer {
    private FixtureMcpServer() {}

    public static void main(String[] args) throws Exception {
        var transport = new StdioServerTransportProvider(McpJsonDefaults.getMapper());
        var server =
                McpServer.sync(transport)
                        .serverInfo("review-fixture-server", "0.1.0")
                        .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                        .tools(ReviewFixtureTool.specification())
                        .build();

        Runtime.getRuntime().addShutdownHook(new Thread(server::closeGracefully));
        // The client controls the subprocess lifecycle through the stdio transport.
        new CountDownLatch(1).await();
    }
}
