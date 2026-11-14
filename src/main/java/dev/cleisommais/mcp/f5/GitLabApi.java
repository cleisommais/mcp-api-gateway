package dev.cleisommais.mcp.f5;

import dev.cleisommais.mcp.f2.Json;
import dev.cleisommais.mcp.f2.ReviewData.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Semantic translation from GitLab REST v4 into the existing immutable review contract. */
public final class GitLabApi implements Source {
    private final GitLabAccess access;
    private final String repository;
    private final int number;
    private final String changePath;
    private Snapshot pinned;
    private Map<String, Object> pinnedMetadata;
    private static final Pattern HUNK = Pattern.compile("^@@ -\\d+(?:,(\\d+))? \\+\\d+(?:,(\\d+))? @@.*");

    public GitLabApi(String repository, int number) { this(GitLabAccess.environment(false), repository, number); }
    public GitLabApi(GitLabAccess access, String repository, int number) {
        GitLabAccess.validateRepository(repository);
        if (number < 1) throw new IllegalArgumentException("Expected a positive MR IID");
        this.access = access; this.repository = repository; this.number = number;
        changePath = "projects/" + GitLabAccess.encode(repository) + "/merge_requests/" + number;
    }

    @Override public Snapshot snapshot() throws Exception {
        if (pinned != null) return pinned;
        var before = metadata();
        var refs = refs(before);
        int expected = expectedFiles(before);
        var files = new ArrayList<FileChange>();
        var seen = new HashSet<String>();
        boolean reachedEnd = false;
        // An operational request bound, never a claim that the first 3,000 files are the entire MR.
        for (int page = 1; page <= 30; page++) {
            var entries = Json.list(access.get(changePath + "/diffs?per_page=100&page=" + page));
            for (Object item : entries) {
                var file = Json.map(item);
                String path = Json.text(file, Boolean.TRUE.equals(file.get("deleted_file")) ? "old_path" : "new_path");
                GitLabAccess.validatePath(path);
                if (!seen.add(path)) throw new IOException("Repeated file during GitLab pagination");
                String patch = file.get("diff") instanceof String text ? text : "";
                boolean complete = !Boolean.TRUE.equals(file.get("collapsed")) && !Boolean.TRUE.equals(file.get("too_large"))
                        && completePatch(patch);
                String status = Boolean.TRUE.equals(file.get("deleted_file")) ? "removed"
                        : Boolean.TRUE.equals(file.get("new_file")) ? "added"
                        : Boolean.TRUE.equals(file.get("renamed_file")) ? "renamed" : "modified";
                files.add(new FileChange(path, status, patch, complete, count(patch, '+'), count(patch, '-'),
                        Boolean.TRUE.equals(file.get("generated_file")) ? "generated" : category(path)));
            }
            if (entries.size() < 100) { reachedEnd = true; break; }
        }
        var after = metadata();
        if (!sameVersion(before, after) || !Objects.equals(before.get("changes_count"), after.get("changes_count"))) {
            throw new IOException("MR snapshot changed during retrieval; rerun against the new commit");
        }
        pinnedMetadata = before;
        pinned = new Snapshot("gitlab", repository, number, Json.text(before, "title"),
                before.get("description") instanceof String text ? text : "", Json.text(refs, "base_sha"),
                Json.text(refs, "head_sha"), Long.toString(id(before, "source_project_id")),
                "opened".equals(before.get("state")) ? "open" : Json.text(before, "state"), expected,
                reachedEnd && expected >= 0 && files.size() == expected, List.copyOf(files),
                Boolean.TRUE.equals(before.get("draft")), Json.text(before, "target_branch"));
        return pinned;
    }

    @Override public String fileAtCommit(String sha, String path) throws Exception {
        if (pinned == null || !pinned.headSha().equals(sha)) throw new IllegalArgumentException("File reads require the pinned head SHA");
        GitLabAccess.validatePath(path);
        var file = Json.map(access.get("projects/" + pinned.headRepository() + "/repository/files/"
                + GitLabAccess.encode(path) + "?ref=" + sha));
        if (!"base64".equals(file.get("encoding")) || !(file.get("size") instanceof Number size)
                || size.longValue() < 0 || size.longValue() > 128_000 || !sha.equals(file.get("commit_id"))
                || !path.equals(file.get("file_path"))) throw new IOException("File metadata is outside the pinned text-read contract");
        byte[] bytes = Base64.getDecoder().decode(Json.text(file, "content"));
        if (bytes.length > 128_000) throw new IOException("Decoded file exceeds the context-read limit");
        for (byte value : bytes) if (value == 0) throw new IOException("Binary file context is not supported");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Override public boolean isCurrent(Snapshot snapshot) throws Exception {
        if (pinned == null || !pinned.equals(snapshot)) throw new IllegalArgumentException("No matching pinned snapshot");
        return sameVersion(pinnedMetadata, metadata());
    }
    @Override public void close() {}

    Map<String, Object> metadata() throws Exception {
        var result = Json.map(access.get(changePath));
        if (id(result, "iid") != number) throw new IOException("GitLab returned a different MR");
        id(result, "project_id"); id(result, "source_project_id");
        return result;
    }
    static Map<String, Object> refs(Map<String, Object> metadata) {
        var refs = Json.map(metadata.get("diff_refs"));
        for (String key : List.of("base_sha", "head_sha", "start_sha")) {
            if (!Json.text(refs, key).matches("[a-f0-9]{40}")) throw new IllegalArgumentException("MR diff refs are not ready or immutable");
        }
        if (!Objects.equals(metadata.get("sha"), refs.get("head_sha"))) throw new IllegalArgumentException("MR diff refs are stale");
        return refs;
    }
    static long id(Map<String, Object> data, String name) {
        Object raw = data.get(name);
        if (!(raw instanceof Integer || raw instanceof Long) || ((Number) raw).longValue() < 1) {
            throw new IllegalArgumentException("Expected a positive GitLab " + name);
        }
        return ((Number) raw).longValue();
    }
    private static boolean sameVersion(Map<String, Object> before, Map<String, Object> after) {
        if (!refs(before).equals(refs(after))) return false;
        return List.of("project_id", "source_project_id", "iid", "state", "draft", "target_branch").stream()
                .allMatch(key -> Objects.equals(before.get(key), after.get(key)));
    }
    private static int expectedFiles(Map<String, Object> metadata) {
        Object count = metadata.get("changes_count");
        try { return count instanceof String text && text.matches("[0-9]{1,9}") ? Integer.parseInt(text) : -1; }
        catch (NumberFormatException ignored) { return -1; }
    }
    private static int count(String patch, char prefix) {
        return (int) patch.lines().filter(line -> !line.isEmpty() && line.charAt(0) == prefix).count();
    }
    // GitLab has no per-file addition/deletion totals here. Validate every hunk's declared lengths.
    static boolean completePatch(String patch) {
        int oldRemaining = 0, newRemaining = 0;
        boolean hunk = false;
        for (String line : patch.split("\n")) {
            var matcher = HUNK.matcher(line);
            if (matcher.matches()) {
                if (oldRemaining != 0 || newRemaining != 0) return false;
                hunk = true;
                oldRemaining = matcher.group(1) == null ? 1 : Integer.parseInt(matcher.group(1));
                newRemaining = matcher.group(2) == null ? 1 : Integer.parseInt(matcher.group(2));
            } else if (line.equals("\\ No newline at end of file")) {
                if (!hunk) return false;
            } else {
                if (!hunk || line.isEmpty()) return false;
                switch (line.charAt(0)) {
                    case '+' -> newRemaining--;
                    case '-' -> oldRemaining--;
                    case ' ' -> { oldRemaining--; newRemaining--; }
                    default -> { return false; }
                }
                if (oldRemaining < 0 || newRemaining < 0) return false;
            }
        }
        return hunk && oldRemaining == 0 && newRemaining == 0;
    }
    private static String category(String path) {
        if (path.contains("/generated/") || path.endsWith(".lock")) return "generated";
        if (path.toLowerCase().contains("test")) return "test";
        if (path.endsWith(".md") || path.endsWith(".txt")) return "documentation";
        if (path.endsWith(".json") || path.endsWith(".yaml") || path.endsWith(".xml")) return "configuration";
        return "code";
    }
}
