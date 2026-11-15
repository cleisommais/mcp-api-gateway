package com.prreview.mcp.server.github;

import com.prreview.common.json.Json;
import com.prreview.platform.github.client.GitHubChangeSource;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/** Read-only GitHub adapter, bound to one PR and its administrative credential mode. */
public final class GitHubMcpServer {
    private GitHubMcpServer() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2)
            throw new IllegalArgumentException("Expected repository and PR number");
        var api = new GitHubChangeSource(args[0], Integer.parseInt(args[1]));
        var empty =
                Map.<String, Object>of(
                        "type", "object", "properties", Map.of(), "additionalProperties", false);
        var fileSchema =
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
        var server =
                McpServer.sync(new StdioServerTransportProvider(McpJsonDefaults.getMapper()))
                        .serverInfo("github-review", "0.2.0")
                        .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                        .tools(
                                tool(
                                        "get_change_snapshot",
                                        "Read metadata and all available file patches, pinning"
                                                + " base/head SHAs.",
                                        empty,
                                        arguments -> api.snapshot()),
                                tool(
                                        "get_file_at_commit",
                                        "Read a text file from this PR at its pinned head SHA"
                                                + " only.",
                                        fileSchema,
                                        arguments ->
                                                Map.of(
                                                        "content",
                                                        api.fileAtCommit(
                                                                Json.text(arguments, "sha"),
                                                                Json.text(arguments, "path")))),
                                tool(
                                        "get_change_state",
                                        "Check whether the pinned base/head/state are still"
                                                + " current.",
                                        empty,
                                        arguments -> Map.of("current", api.isCurrent())))
                        .build();
        Runtime.getRuntime().addShutdownHook(new Thread(server::closeGracefully));
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
                                                error.getMessage() == null
                                                        ? "Read operation failed"
                                                        : error.getMessage())
                                        .build();
                            }
                        })
                .build();
    }
}
