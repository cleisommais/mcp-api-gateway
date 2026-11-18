package com.prreview.platform.gitlab.client;

import com.prreview.common.json.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Local REST fixture shared by semantic, publication and cross-platform contract checks. */
public final class GitLabFixture implements AutoCloseable {
    public static final String BASE = "a".repeat(40), HEAD = "b".repeat(40);
    public static final String REPO = "group/subgroup/repo";
    public static final String PATCH = "@@ -1 +1 @@\n-return amount;\n+return amount * 100;\n";

    public record Response(int status, Object body) {}

    @FunctionalInterface
    public interface Handler {
        Response handle(HttpExchange exchange) throws Exception;
    }

    private final HttpServer server;
    public final List<String> requests = new ArrayList<>();
    public final List<String> tokens = new ArrayList<>();

    public GitLabFixture(Handler handler) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
                    tokens.add(exchange.getRequestHeaders().getFirst("PRIVATE-TOKEN"));
                    try {
                        var response = handler.handle(exchange);
                        byte[] bytes = Json.write(response.body()).getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(response.status(), bytes.length);
                        try (var output = exchange.getResponseBody()) {
                            output.write(bytes);
                        }
                    } catch (Exception error) {
                        exchange.sendResponseHeaders(500, -1);
                        exchange.close();
                    }
                });
        server.start();
    }

    public static Response ok(Object body) {
        return new Response(200, body);
    }

    public GitLabApiClient access(boolean publishing) {
        return new GitLabApiClient(
                origin(), publishing ? "test-publish-token" : "test-read-token", publishing);
    }

    public URI origin() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    public GitLabChangeSource api() {
        return new GitLabChangeSource(access(false), REPO, 1);
    }

    public static Map<String, Object> metadata(String count) {
        var result = new LinkedHashMap<String, Object>();
        result.put("iid", 1);
        result.put("project_id", 42);
        result.put("source_project_id", 43);
        result.put("title", "Change");
        result.put("description", "");
        result.put("state", "opened");
        result.put("draft", false);
        result.put("target_branch", "main");
        result.put("sha", HEAD);
        result.put("diff_refs", Map.of("base_sha", BASE, "head_sha", HEAD, "start_sha", BASE));
        result.put("changes_count", count);
        return result;
    }

    public static Map<String, Object> file(String path) {
        return Map.of(
                "new_path",
                path,
                "old_path",
                path,
                "diff",
                PATCH,
                "new_file",
                false,
                "deleted_file",
                false,
                "renamed_file",
                false);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
