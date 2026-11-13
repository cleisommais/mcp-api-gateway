package dev.cleisommais.mcp.f2;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static dev.cleisommais.mcp.f2.ReviewData.*;

/** Narrow public-repository adapter. No GitHub write endpoint or personal token is exposed. */
final class PublicGitHubApi {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final URI base;
    private final String repository;
    private final int number;
    private Snapshot pinned;

    PublicGitHubApi(String repository, int number) {
        this(URI.create("https://api.github.com/"), repository, number);
    }

    // The alternate base is only injected by local HTTP integration tests.
    PublicGitHubApi(URI base, String repository, int number) {
        if (!repository.matches("[A-Za-z0-9_-]+/[A-Za-z0-9_.-]+") || repository.endsWith("/..")
                || repository.endsWith("/.") || number < 1) {
            throw new IllegalArgumentException("Expected owner/repository and a positive PR number");
        }
        this.base = base;
        this.repository = repository;
        this.number = number;
    }

    Snapshot snapshot() throws Exception {
        var metadata = metadata();
        String head = sha(Json.map(metadata.get("head")));
        String baseSha = sha(Json.map(metadata.get("base")));
        String headRepository = Json.text(Json.map(Json.map(metadata.get("head")).get("repo")), "full_name");
        if (!headRepository.matches("[A-Za-z0-9_-]+/[A-Za-z0-9_.-]+")) {
            throw new IOException("Invalid head repository returned by GitHub");
        }
        int expected = ((Number) metadata.get("changed_files")).intValue();
        var files = new ArrayList<FileChange>();
        var seen = new HashSet<String>();
        boolean reachedEnd = false;
        // GitHub's PR-files endpoint exposes at most 3,000 files. Report the limit explicitly.
        for (int page = 1; page <= 30; page++) {
            var entries = Json.list(get("repos/" + repository + "/pulls/" + number
                    + "/files?per_page=100&page=" + page));
            for (Object entry : entries) {
                var file = Json.map(entry);
                String path = Json.text(file, "filename");
                if (!seen.add(path)) throw new IOException("Repeated file during GitHub pagination");
                String patch = file.get("patch") instanceof String text ? text : "";
                int additions = ((Number) file.get("additions")).intValue();
                int deletions = ((Number) file.get("deletions")).intValue();
                boolean complete = !patch.isBlank() && countLines(patch, '+') == additions
                        && countLines(patch, '-') == deletions;
                files.add(new FileChange(path, Json.text(file, "status"), patch, complete,
                        additions, deletions, category(path)));
            }
            if (entries.size() < 100) { reachedEnd = true; break; }
        }
        var after = metadata();
        if (!head.equals(sha(Json.map(after.get("head")))) || !baseSha.equals(sha(Json.map(after.get("base"))))) {
            throw new IOException("PR snapshot changed during retrieval; rerun against the new commit");
        }
        pinned = new Snapshot("github", repository, number, Json.text(metadata, "title"),
                metadata.get("body") instanceof String body ? body : "", baseSha, head, headRepository,
                Json.text(after, "state"), expected, reachedEnd && files.size() == expected, List.copyOf(files));
        return pinned;
    }

    String fileAtCommit(String requestedSha, String path) throws Exception {
        if (pinned == null || !pinned.headSha().equals(requestedSha)) {
            throw new IllegalArgumentException("File reads require the pinned head SHA");
        }
        String encoded = encodePath(path);
        var file = Json.map(get("repos/" + pinned.headRepository() + "/contents/" + encoded + "?ref=" + requestedSha));
        if (!"file".equals(file.get("type")) || !"base64".equals(file.get("encoding"))
                || ((Number) file.get("size")).longValue() > 128_000) {
            throw new IOException("File is unavailable, not a regular text file, or exceeds the context-read limit");
        }
        byte[] content = Base64.getMimeDecoder().decode(Json.text(file, "content"));
        if (content.length > 128_000) throw new IOException("Decoded file exceeds the context-read limit");
        for (byte value : content) if (value == 0) throw new IOException("Binary file context is not supported");
        return new String(content, StandardCharsets.UTF_8);
    }

    boolean isCurrent() throws Exception {
        if (pinned == null) throw new IllegalStateException("No pinned snapshot");
        var current = metadata();
        return pinned.headSha().equals(sha(Json.map(current.get("head"))))
                && pinned.baseSha().equals(sha(Json.map(current.get("base"))))
                && pinned.state().equals(current.get("state"));
    }

    private Map<String, Object> metadata() throws Exception {
        return Json.map(get("repos/" + repository + "/pulls/" + number));
    }

    private Object get(String path) throws Exception {
        var request = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(25))
                .header("Accept", "application/vnd.github+json").header("User-Agent", "mcp-api-gateway-poc")
                .header("X-GitHub-Api-Version", "2022-11-28").GET().build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        String body = Json.boundedBody(response.body(), 2_000_000);
        if (response.statusCode() != 200) {
            throw new IOException("GitHub HTTP " + response.statusCode()
                    + "; check public access and API rate limits before retrying");
        }
        return Json.read(body, new io.modelcontextprotocol.json.TypeRef<Object>() {});
    }

    static String encodePath(String path) {
        if (path == null || path.isBlank() || path.startsWith("/") || path.contains("\\") || path.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid repository-relative path");
        }
        var result = new ArrayList<String>();
        for (String part : path.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                throw new IllegalArgumentException("Path traversal is not allowed");
            }
            result.add(URLEncoder.encode(part, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return String.join("/", result);
    }

    private static String sha(Map<String, Object> ref) {
        String sha = Json.text(ref, "sha");
        if (!sha.matches("[a-f0-9]{40}")) throw new IllegalArgumentException("Expected a full immutable SHA");
        return sha;
    }

    private static int countLines(String patch, char prefix) {
        return (int) patch.lines().filter(line -> !line.isEmpty() && line.charAt(0) == prefix).count();
    }

    private static String category(String path) {
        if (path.contains("/generated/") || path.endsWith(".lock")) return "generated";
        if (path.toLowerCase().contains("test")) return "test";
        if (path.endsWith(".md") || path.endsWith(".txt")) return "documentation";
        if (path.endsWith(".json") || path.endsWith(".yaml") || path.endsWith(".xml")) return "configuration";
        return "code";
    }
}
