package com.prreview.platform.bitbucket.client;

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

public final class BitbucketFixture implements AutoCloseable {
    public static final String REPO = "workspace/repo", ROOT = "/repositories/" + REPO;
    public static final String BASE = "a".repeat(40), HEAD = "b".repeat(40);
    public static final String ACTOR = "{00000000-0000-0000-0000-000000000001}";
    public static final String PATCH = "@@ -1 +1 @@\n-return amount;\n+return amount * 100;\n";
    public static final String DIFF =
            "diff --git a/Money.java b/Money.java\n--- a/Money.java\n+++ b/Money.java\n" + PATCH;

    public record Response(int status, Object body, String location) {}

    @FunctionalInterface
    public interface Handler {
        Response handle(HttpExchange exchange) throws Exception;
    }

    private final HttpServer server;
    public final List<String> requests = new ArrayList<>(), credentials = new ArrayList<>();

    public BitbucketFixture(Handler handler) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
                    credentials.add(exchange.getRequestHeaders().getFirst("Authorization"));
                    try {
                        var response = handler.handle(exchange);
                        if (response.location() != null)
                            exchange.getResponseHeaders().set("Location", response.location());
                        byte[] bytes =
                                (response.body() instanceof String text
                                                ? text
                                                : Json.write(response.body()))
                                        .getBytes(StandardCharsets.UTF_8);
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
        return new Response(200, body, null);
    }

    public static Response redirect(String location) {
        return new Response(302, "", location);
    }

    public URI origin() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    public BitbucketApiClient access(boolean publishing) {
        return new BitbucketApiClient(
                origin(),
                REPO,
                "fixture@example.invalid",
                publishing ? "publish-token" : "read-token",
                publishing);
    }

    public BitbucketChangeSource source() {
        return new BitbucketChangeSource(access(false), REPO, 1);
    }

    public static Map<String, Object> metadata() {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", 1);
        result.put("title", "Change");
        result.put("description", "");
        result.put("state", "OPEN");
        result.put("draft", false);
        result.put("source", endpoint(HEAD, "feature"));
        result.put("destination", endpoint(BASE, "main"));
        return result;
    }

    public static Map<String, Object> endpoint(String sha, String branch) {
        return Map.of(
                "commit",
                Map.of("hash", sha),
                "branch",
                Map.of("name", branch),
                "repository",
                Map.of("full_name", REPO));
    }

    public static Map<String, Object> file(String path) {
        return Map.of(
                "status",
                "modified",
                "new",
                Map.of("path", path),
                "old",
                Map.of("path", path),
                "lines_added",
                1,
                "lines_removed",
                1);
    }

    public static Response standard(HttpExchange exchange) {
        String path = exchange.getRequestURI().getPath();
        if (path.contains("/diffstat/"))
            return ok(Map.of("size", 1, "values", List.of(file("Money.java"))));
        if (path.contains("/diff/")) return ok(DIFF);
        if (path.contains("/src/")) return ok("return amount * 100;");
        if (path.equals("/user")) return ok(Map.of("uuid", ACTOR));
        return ok(metadata());
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
