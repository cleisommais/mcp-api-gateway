package com.prreview.mcp.server;

import com.prreview.common.json.Json;
import com.prreview.domain.model.ChangeSnapshot;
import com.prreview.domain.port.ChangeSource;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/** The same three read-only MCP operations for every platform adapter. */
public final class ReadOnlyReviewMcpServer {
    private ReadOnlyReviewMcpServer() {}

    public static void run(String name, String version, ChangeSource source) throws Exception {
        var empty =
                Map.<String, Object>of(
                        "type", "object", "properties", Map.of(), "additionalProperties", false);
        var file =
                Map.<String, Object>of(
                        "type",
                        "object",
                        "properties",
                        Map.of(
                                "sha", Map.of("type", "string", "pattern", "^[a-f0-9]{40}$"),
                                "path", Map.of("type", "string", "minLength", 1)),
                        "required",
                        List.of("sha", "path"),
                        "additionalProperties",
                        false);
        var pinned = new ChangeSnapshot[1];
        var server =
                McpServer.sync(new StdioServerTransportProvider(McpJsonDefaults.getMapper()))
                        .serverInfo(name, version)
                        .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                        .tools(
                                tool(
                                        "get_change_snapshot",
                                        "Read metadata and all available file patches, pinning"
                                                + " base/head SHAs.",
                                        empty,
                                        arguments -> pinned[0] = source.snapshot()),
                                tool(
                                        "get_file_at_commit",
                                        "Read a text file from this PR at its pinned head SHA"
                                                + " only.",
                                        file,
                                        arguments ->
                                                Map.of(
                                                        "content",
                                                        source.fileAtCommit(
                                                                Json.text(arguments, "sha"),
                                                                Json.text(arguments, "path")))),
                                tool(
                                        "get_change_state",
                                        "Check whether the pinned base/head/state are still"
                                                + " current.",
                                        empty,
                                        arguments ->
                                                Map.of("current", source.isCurrent(pinned[0]))))
                        .build();
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    server.closeGracefully();
                                    source.close();
                                }));
        new CountDownLatch(1).await();
    }

    @FunctionalInterface
    private interface Operation {
        Object run(Map<String, Object> arguments) throws Exception;
    }

    private static McpServerFeatures.SyncToolSpecification tool(
            String name, String description, Map<String, Object> schema, Operation operation) {
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder(name, schema).description(description).build())
                .callHandler(
                        (exchange, request) -> {
                            try {
                                Object result = operation.run(request.arguments());
                                return McpSchema.CallToolResult.builder()
                                        .structuredContent(result)
                                        .addTextContent(Json.write(result))
                                        .isError(false)
                                        .build();
                            } catch (Exception error) {
                                return McpSchema.CallToolResult.builder()
                                        .isError(true)
                                        .addTextContent(
                                                error instanceof IllegalArgumentException
                                                                || error
                                                                        instanceof SecurityException
                                                        ? error.getMessage()
                                                        : "Platform read failed; check access,"
                                                                + " limits and snapshot state")
                                        .build();
                            }
                        })
                .build();
    }
}
