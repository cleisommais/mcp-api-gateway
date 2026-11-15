package com.prreview.mcp.client;

import com.prreview.common.json.Json;
import com.prreview.domain.model.ChangeSnapshot;
import com.prreview.domain.port.ChangeSource;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Agent-side MCP client. No direct GitHub transport is created in gateway mode. */
public final class GatewayMcpClient implements ChangeSource {
    private final McpSyncClient client;
    private final String jobId;
    private String serverAlias = "unresolved";
    private final String repository;
    private final int number;
    private final String platform;
    private ChangeSnapshot pinned;

    public GatewayMcpClient(URI endpoint, String secret, String repository, int number) {
        this(endpoint, secret, repository, number, UUID.randomUUID());
    }

    public GatewayMcpClient(
            URI endpoint, String secret, String repository, int number, UUID jobId) {
        this(endpoint, secret, "github", repository, number, jobId);
    }

    public GatewayMcpClient(
            URI endpoint,
            String secret,
            String platform,
            String repository,
            int number,
            UUID jobId) {
        if (!Set.of("github", "gitlab").contains(platform))
            throw new IllegalArgumentException("Unsupported review platform");
        this.platform = platform;
        this.jobId = jobId.toString();
        if (!Set.of("http", "https").contains(endpoint.getScheme())
                || endpoint.getHost() == null
                || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null
                || endpoint.getFragment() != null
                || secret == null
                || secret.length() < 32)
            throw new IllegalArgumentException("Expected gateway URL and lab credential");
        this.repository = repository;
        this.number = number;
        var transport =
                HttpClientStreamableHttpTransport.builder(endpoint.toString())
                        .endpoint("/mcp")
                        .requestBuilder(
                                HttpRequest.newBuilder()
                                        .header("Authorization", "Bearer " + secret))
                        .build();
        client =
                McpClient.sync(transport)
                        .initializationTimeout(Duration.ofSeconds(15))
                        .requestTimeout(Duration.ofMinutes(3))
                        .build();
        try {
            var initialized = client.initialize();
            if (!"review-mcp-gateway".equals(initialized.serverInfo().name())
                    || !"0.3.0".equals(initialized.serverInfo().version())
                    || !"2025-11-25".equals(initialized.protocolVersion())) {
                throw new IllegalStateException("Unexpected gateway profile or catalog");
            }
            var catalog =
                    client.listTools().tools().stream()
                            .map(McpSchema.Tool::name)
                            .collect(Collectors.toSet());
            if (!com.prreview.mcpgateway.config.ReviewToolCatalog.schemas()
                            .keySet()
                            .containsAll(catalog)
                    || !catalog.containsAll(
                            com.prreview.mcpgateway.config.ReviewToolCatalog.schemas(
                                            Set.of(platform))
                                    .keySet())) {
                throw new IllegalStateException("Unexpected gateway catalog");
            }
        } catch (RuntimeException error) {
            client.closeGracefully();
            throw error;
        }
    }

    @Override
    public ChangeSnapshot snapshot() throws Exception {
        if (pinned == null) {
            var data =
                    new LinkedHashMap<>(
                            Json.map(call(platform + ".get_change_snapshot", scope(false))));
            if (data.get("_gateway") instanceof Map<?, ?>)
                serverAlias = Json.text(Json.map(data.remove("_gateway")), "server");
            pinned = Json.read(Json.write(data), new TypeRef<>() {});
            if (!platform.equals(pinned.platform())
                    || !repository.equals(pinned.repository())
                    || number != pinned.number()) {
                throw new SecurityException("Gateway returned a different resource");
            }
        }
        return pinned;
    }

    @Override
    public String fileAtCommit(String sha, String path) throws Exception {
        var arguments = scope(true);
        arguments.put("sha", sha);
        arguments.put("path", path);
        return Json.text(Json.map(call(platform + ".get_file_at_commit", arguments)), "content");
    }

    @Override
    public boolean isCurrent(ChangeSnapshot snapshot) throws Exception {
        return Boolean.TRUE.equals(
                Json.map(call(platform + ".get_change_state", scope(true))).get("current"));
    }

    public Map<String, Object> trace() {
        return Map.of(
                "mode", "gateway", "jobId", jobId, "server", serverAlias, "protocol", "2025-11-25");
    }

    private LinkedHashMap<String, Object> scope(boolean withSha) {
        var arguments = new LinkedHashMap<String, Object>();
        arguments.put("jobId", jobId);
        arguments.put("repository", repository);
        arguments.put("number", number);
        if (withSha) {
            if (pinned == null) throw new IllegalStateException("Retrieve a snapshot first");
            arguments.put("sha", pinned.headSha());
        }
        return arguments;
    }

    private Object call(String name, Map<String, Object> arguments) throws IOException {
        var result =
                client.callTool(
                        McpSchema.CallToolRequest.builder(name).arguments(arguments).build());
        if (Boolean.TRUE.equals(result.isError()) || result.structuredContent() == null)
            throw new IOException("Gateway call failed: " + result.content());
        return result.structuredContent();
    }

    @Override
    public void close() {
        try {
            if (pinned != null) call(platform + ".close_review", scope(true));
        } catch (Exception error) {
            System.err.println(
                    "Gateway review cleanup failed; restart gateway to release abandoned lab"
                            + " jobs.");
        } finally {
            client.closeGracefully();
        }
    }
}
