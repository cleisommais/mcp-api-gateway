package dev.cleisommais.mcp.f5;

import dev.cleisommais.mcp.f2.Json;
import io.modelcontextprotocol.json.TypeRef;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** GitLab.com only in production. Redirects, response bodies and retries are bounded. */
public final class GitLabAccess {
    public static final class RequestException extends IOException {
        private final int status;
        RequestException(int status) {
            super("GitLab HTTP " + status + "; check project permissions, token scope and rate limits");
            this.status = status;
        }
        public int status() { return status; }
    }
    private final URI base;
    private final String token;
    private final boolean publishing;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    // Alternate origins are injected only by local integration tests, never by a tool argument.
    GitLabAccess(URI base, String token, boolean publishing) {
        this.base = base;
        this.token = token;
        this.publishing = publishing;
        if (publishing && token.isBlank()) throw new IllegalArgumentException("GITLAB_PUBLISH_TOKEN is required");
    }

    public static GitLabAccess environment(boolean publishing) {
        return new GitLabAccess(URI.create("https://gitlab.com/api/v4/"),
                System.getenv().getOrDefault(publishing ? "GITLAB_PUBLISH_TOKEN" : "GITLAB_READ_TOKEN", ""), publishing);
    }

    Object get(String path) throws Exception { return request("GET", path, null); }
    Object request(String method, String path, Object payload) throws Exception {
        if (!method.equals("GET") && (!publishing || !method.matches("POST|PUT")
                || !path.matches("projects/[1-9][0-9]*/merge_requests/[1-9][0-9]*/notes(?:/[1-9][0-9]*)?"))) {
            throw new SecurityException("Only the controlled publisher can write MR summary notes");
        }
        if (!(path.startsWith("projects/") || path.equals("user")) || path.contains("/../") || path.contains("/./")
                || path.contains(":") || path.contains("\\") || path.startsWith("/")) {
            throw new SecurityException("Invalid GitLab API path");
        }
        var builder = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(25))
                .header("Accept", "application/json").header("User-Agent", "mcp-review-poc");
        if (!token.isBlank()) builder.header("PRIVATE-TOKEN", token);
        builder.method(method, payload == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(Json.write(payload)));
        if (payload != null) builder.header("Content-Type", "application/json");
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        try (var input = response.body()) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw new RequestException(response.statusCode());
            byte[] bytes = input.readNBytes(2_000_001);
            if (bytes.length > 2_000_000) throw new IOException("GitLab response exceeded the byte limit");
            return Json.read(new String(bytes, StandardCharsets.UTF_8), new TypeRef<Object>() {});
        }
    }

    public static void validateRepository(String repository) {
        if (repository == null || !repository.matches("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_.-]+)+")) {
            throw new IllegalArgumentException("Expected a GitLab namespace/project path");
        }
        for (String segment : repository.split("/")) {
            if (segment.equals(".") || segment.equals("..")) throw new IllegalArgumentException("Invalid project path");
        }
    }
    static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
    static void validatePath(String path) {
        if (path == null || path.isBlank() || path.startsWith("/") || path.contains("\\") || path.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid repository-relative path");
        }
        for (String part : path.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new IllegalArgumentException("Path traversal denied");
        }
    }
}
