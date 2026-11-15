package com.prreview.mcp.client;

import com.prreview.common.json.Json;
import com.prreview.domain.model.ChangeSnapshot;
import com.prreview.domain.port.ChangeSource;
import com.prreview.mcp.server.github.GitHubMcpServer;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class StdioMcpClient implements ChangeSource {
    private final McpSyncClient client;
    private final McpSchema.InitializeResult initialized;

    public StdioMcpClient(String repository, int number) {
        this(repository, number, Map.of());
    }

    public StdioMcpClient(String repository, int number, Map<String, String> environment) {
        this("github", repository, number, environment);
    }

    public StdioMcpClient(
            String platform, String repository, int number, Map<String, String> environment) {
        String mainClass =
                switch (platform) {
                    case "github" -> GitHubMcpServer.class.getName();
                    case "gitlab" -> com.prreview.mcp.server.gitlab.GitLabMcpServer.class.getName();
                    default -> throw new IllegalArgumentException("Unsupported review platform");
                };
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var parameters =
                ServerParameters.builder(java)
                        .args(
                                "-Dorg.slf4j.simpleLogger.defaultLogLevel=off",
                                "-cp",
                                System.getProperty(
                                        "surefire.test.class.path",
                                        System.getProperty("java.class.path")),
                                mainClass,
                                repository,
                                Integer.toString(number))
                        .env(environment)
                        .build();
        var transport =
                new StdioClientTransport(parameters, McpJsonDefaults.getMapper()) {
                    @Override
                    protected ProcessBuilder getProcessBuilder() {
                        var builder = super.getProcessBuilder();
                        // ServerParameters restores only the SDK's small platform environment
                        // allowlist.
                        builder.environment().clear();
                        return builder;
                    }
                };
        client =
                McpClient.sync(transport)
                        .initializationTimeout(Duration.ofSeconds(15))
                        .requestTimeout(Duration.ofMinutes(3))
                        .build();
        try {
            initialized = client.initialize();
            var names =
                    client.listTools().tools().stream()
                            .map(McpSchema.Tool::name)
                            .collect(Collectors.toSet());
            if (!names.equals(
                    Set.of("get_change_snapshot", "get_file_at_commit", "get_change_state"))) {
                throw new IllegalStateException("Unexpected MCP catalog");
            }
        } catch (RuntimeException error) {
            client.closeGracefully();
            throw error;
        }
    }

    public McpSchema.InitializeResult initialized() {
        return initialized;
    }

    public java.util.List<McpSchema.Tool> catalog() {
        return client.listTools().tools();
    }

    @Override
    public ChangeSnapshot snapshot() throws Exception {
        return Json.read(Json.write(call("get_change_snapshot", Map.of())), new TypeRef<>() {});
    }

    @Override
    public String fileAtCommit(String sha, String path) throws Exception {
        return Json.text(
                Json.map(call("get_file_at_commit", Map.of("sha", sha, "path", path))), "content");
    }

    @Override
    public boolean isCurrent(ChangeSnapshot snapshot) throws Exception {
        return Boolean.TRUE.equals(Json.map(call("get_change_state", Map.of())).get("current"));
    }

    private Object call(String name, Map<String, Object> arguments) throws IOException {
        var result =
                client.callTool(
                        McpSchema.CallToolRequest.builder(name).arguments(arguments).build());
        if (Boolean.TRUE.equals(result.isError()))
            throw new IOException("MCP read failed: " + result.content());
        if (result.structuredContent() == null)
            throw new IOException("Missing structured MCP result");
        return result.structuredContent();
    }

    @Override
    public void close() {
        client.closeGracefully();
    }
}
