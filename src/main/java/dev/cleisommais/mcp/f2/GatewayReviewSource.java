package dev.cleisommais.mcp.f2;

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

import static dev.cleisommais.mcp.f2.ReviewData.*;

/** Agent-side MCP client. No direct GitHub transport is created in gateway mode. */
final class GatewayReviewSource implements Source {
    private final McpSyncClient client;
    private final String jobId;
    private String serverAlias = "unresolved";
    private final String repository;
    private final int number;
    private Snapshot pinned;

    GatewayReviewSource(URI endpoint, String secret, String repository, int number) {
        this(endpoint, secret, repository, number, UUID.randomUUID());
    }
    GatewayReviewSource(URI endpoint, String secret, String repository, int number, UUID jobId) {
        this.jobId = jobId.toString();
        if (!Set.of("http", "https").contains(endpoint.getScheme()) || endpoint.getHost() == null
                || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null
                || secret == null || secret.length() < 32) throw new IllegalArgumentException("Expected gateway URL and lab credential");
        this.repository = repository;
        this.number = number;
        var transport = HttpClientStreamableHttpTransport.builder(endpoint.toString()).endpoint("/mcp")
                .requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer " + secret)).build();
        client = McpClient.sync(transport).initializationTimeout(Duration.ofSeconds(15)).requestTimeout(Duration.ofMinutes(3)).build();
        try {
            var initialized = client.initialize();
            if (!"review-mcp-gateway".equals(initialized.serverInfo().name()) || !"0.3.0".equals(initialized.serverInfo().version())
                    || !"2025-11-25".equals(initialized.protocolVersion()) || !client.listTools().tools().stream()
                    .map(McpSchema.Tool::name).collect(Collectors.toSet()).equals(Set.of("github.get_change_snapshot",
                            "github.get_file_at_commit", "github.get_change_state", "github.close_review"))) {
                throw new IllegalStateException("Unexpected gateway profile or catalog");
            }
        } catch (RuntimeException error) { client.closeGracefully(); throw error; }
    }

    @Override public Snapshot snapshot() throws Exception {
        if (pinned == null) {
            var data = new LinkedHashMap<>(Json.map(call("github.get_change_snapshot", scope(false))));
            if (data.get("_gateway") instanceof Map<?, ?>) serverAlias = Json.text(Json.map(data.remove("_gateway")), "server");
            pinned = Json.read(Json.write(data), new TypeRef<>() {});
        }
        return pinned;
    }

    @Override public String fileAtCommit(String sha, String path) throws Exception {
        var arguments = scope(true);
        arguments.put("sha", sha);
        arguments.put("path", path);
        return Json.text(Json.map(call("github.get_file_at_commit", arguments)), "content");
    }

    @Override public boolean isCurrent(Snapshot snapshot) throws Exception {
        return Boolean.TRUE.equals(Json.map(call("github.get_change_state", scope(true))).get("current"));
    }

    Map<String, Object> trace() { return Map.of("mode", "gateway", "jobId", jobId, "server", serverAlias, "protocol", "2025-11-25"); }

    private LinkedHashMap<String, Object> scope(boolean withSha) {
        var arguments = new LinkedHashMap<String, Object>();
        arguments.put("jobId", jobId); arguments.put("repository", repository); arguments.put("number", number);
        if (withSha) {
            if (pinned == null) throw new IllegalStateException("Retrieve a snapshot first");
            arguments.put("sha", pinned.headSha());
        }
        return arguments;
    }

    private Object call(String name, Map<String, Object> arguments) throws IOException {
        var result = client.callTool(McpSchema.CallToolRequest.builder(name).arguments(arguments).build());
        if (Boolean.TRUE.equals(result.isError()) || result.structuredContent() == null) throw new IOException("Gateway call failed: " + result.content());
        return result.structuredContent();
    }

    @Override public void close() {
        try { if (pinned != null) call("github.close_review", scope(true)); }
        catch (Exception error) { System.err.println("Gateway review cleanup failed; restart gateway to release abandoned lab jobs."); }
        finally { client.closeGracefully(); }
    }
}
