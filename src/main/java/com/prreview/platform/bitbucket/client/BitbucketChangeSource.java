package com.prreview.platform.bitbucket.client;

import com.prreview.common.json.Json;
import com.prreview.domain.model.ChangeSnapshot;
import com.prreview.domain.model.FileChange;
import com.prreview.domain.port.ChangeSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Bitbucket Cloud PRs translated into the immutable, platform-neutral review contract. */
public final class BitbucketChangeSource implements ChangeSource {
    private static final Pattern HUNK =
            Pattern.compile("^@@ -\\d+(?:,(\\d+))? \\+\\d+(?:,(\\d+))? @@.*");
    private final BitbucketApiClient access;
    private final String repository;
    private final int number;
    private final String changePath;
    private Map<String, Object> pinnedMetadata;
    private ChangeSnapshot pinned;

    public BitbucketChangeSource(String repository, int number) {
        this(BitbucketApiClient.environment(repository, false), repository, number);
    }

    public BitbucketChangeSource(BitbucketApiClient access, String repository, int number) {
        BitbucketApiClient.validateRepository(repository);
        if (number < 1) throw new IllegalArgumentException("Expected a positive PR number");
        this.access = access;
        this.repository = repository;
        this.number = number;
        changePath = access.repositoryPath() + "/pullrequests/" + number;
    }

    @Override
    public ChangeSnapshot snapshot() throws Exception {
        if (pinned != null) return pinned;
        var before = metadata();
        String base = sha(before, "destination"), head = sha(before, "source");
        // Bitbucket's source..destination order is the reverse of git diff.
        String comparison = head + ".." + base;
        String diffstat = access.repositoryPath() + "/diffstat/" + comparison;
        String next = diffstat + "?topic=true&pagelen=100&page=1";
        var seenPages = new HashSet<String>();
        var seenFiles = new HashSet<String>();
        var files = new ArrayList<FileChange>();
        int expected = -1;
        boolean complete = false;
        for (int page = 0; page < 30; page++) {
            if (!seenPages.add(next)) throw new IOException("Repeated Bitbucket diffstat page");
            var response = Json.map(access.get(next));
            if (response.get("size") instanceof Number size) {
                if (size.longValue() < 0 || size.longValue() > Integer.MAX_VALUE)
                    throw new IOException("Invalid Bitbucket diffstat size");
                int count = size.intValue();
                if (expected >= 0 && expected != count)
                    throw new IOException("Bitbucket diffstat size changed");
                expected = count;
            }
            for (Object entry : Json.list(response.get("values"))) {
                var file = Json.map(entry);
                String rawStatus = Json.text(file, "status");
                String path =
                        Json.text(
                                Json.map(file.get(rawStatus.equals("removed") ? "old" : "new")),
                                "path");
                BitbucketApiClient.validatePath(path);
                if (!seenFiles.add(path))
                    throw new IOException("Repeated file during Bitbucket pagination");
                int additions = count(file, "lines_added"),
                        deletions = count(file, "lines_removed");
                String diff;
                try {
                    diff =
                            access.text(
                                    access.repositoryPath()
                                            + "/diff/"
                                            + comparison
                                            + "?topic=true&path="
                                            + BitbucketApiClient.encode(path),
                                    2_000_000);
                } catch (BitbucketApiClient.RequestException error) {
                    if (error.status() != 413 && error.status() != 555) throw error;
                    diff = "";
                }
                String patch = patch(diff);
                boolean patchComplete = completePatch(patch, additions, deletions);
                if (rawStatus.equals("renamed")
                        && additions == 0
                        && deletions == 0
                        && diff.contains("similarity index 100%")
                        && patch.isEmpty()) patchComplete = true;
                String status =
                        switch (rawStatus) {
                            case "added", "removed", "renamed", "modified" -> rawStatus;
                            default -> "modified";
                        };
                files.add(
                        new FileChange(
                                path,
                                status,
                                patch,
                                patchComplete,
                                additions,
                                deletions,
                                category(path)));
            }
            if (!(response.get("next") instanceof String href) || href.isBlank()) {
                complete = true;
                break;
            }
            next = access.pagePath(href, diffstat);
            // Pagination must preserve the merge-base comparison semantics.
            String query = java.net.URI.create(next).getRawQuery();
            if (query == null
                    || !java.util.Arrays.stream(query.split("&"))
                            .filter(part -> part.startsWith("topic="))
                            .toList()
                            .equals(List.of("topic=true"))
                    || java.util.Arrays.stream(query.split("&"))
                            .anyMatch(
                                    part ->
                                            !part.matches(
                                                    "topic=true|page=[1-9][0-9]*|pagelen=[1-9][0-9]*")))
                throw new SecurityException("Bitbucket pagination changed diff semantics");
        }
        if (!sameVersion(before, metadata()))
            throw new IOException("PR changed during Bitbucket snapshot retrieval");
        if (complete && expected < 0) expected = files.size();
        pinnedMetadata = before;
        pinned =
                new ChangeSnapshot(
                        "bitbucket",
                        repository,
                        number,
                        Json.text(before, "title"),
                        before.get("description") instanceof String text ? text : "",
                        base,
                        head,
                        repository,
                        "OPEN".equals(before.get("state"))
                                ? "open"
                                : Json.text(before, "state").toLowerCase(java.util.Locale.ROOT),
                        expected,
                        complete && expected == files.size(),
                        List.copyOf(files),
                        Boolean.TRUE.equals(before.get("draft")),
                        branch(before));
        return pinned;
    }

    @Override
    public String fileAtCommit(String sha, String path) throws Exception {
        BitbucketApiClient.validatePath(path);
        if (pinned == null
                || !pinned.headSha().equals(sha)
                || pinned.files().stream()
                        .noneMatch(
                                file ->
                                        file.path().equals(path)
                                                && !file.status().equals("removed")))
            throw new IllegalArgumentException("File read is outside the pinned PR head");
        String encodedPath =
                String.join(
                        "/",
                        java.util.Arrays.stream(path.split("/"))
                                .map(BitbucketApiClient::encode)
                                .toList());
        String content =
                access.text(access.repositoryPath() + "/src/" + sha + "/" + encodedPath, 128_000);
        if (content.indexOf('\0') >= 0)
            throw new IOException("Binary file context is not supported");
        return content;
    }

    public Map<String, Object> metadata() throws Exception {
        var result = new java.util.HashMap<>(Json.map(access.get(changePath)));
        if (id(result) != number) throw new IOException("Bitbucket returned a different PR");
        for (String side : List.of("source", "destination")) {
            var endpoint = new java.util.HashMap<>(Json.map(result.get(side)));
            // Fork credentials/routing are deliberately outside the first Cloud adapter.
            if (!repository.equals(Json.map(endpoint.get("repository")).get("full_name")))
                throw new SecurityException(
                        "Bitbucket PR must use the configured repository on both sides");
            var commit = new java.util.HashMap<>(Json.map(endpoint.get("commit")));
            String ref = Json.text(commit, "hash");
            if (!ref.matches("[a-f0-9]{7,40}"))
                throw new IllegalArgumentException("Expected a Bitbucket commit hash");
            // PR responses embed abbreviated hashes. Resolve through the commit
            // endpoint before pinning, diffing or comparing with a queued job.
            if (ref.length() < 40) {
                String resolved =
                        Json.text(
                                Json.map(access.get(access.repositoryPath() + "/commit/" + ref)),
                                "hash");
                if (!resolved.matches("[a-f0-9]{40}") || !resolved.startsWith(ref))
                    throw new SecurityException(
                            "Bitbucket commit resolution did not match the PR ref");
                commit.put("hash", resolved);
            }
            endpoint.put("commit", commit);
            result.put(side, endpoint);
            sha(result, side);
        }
        branch(result);
        return result;
    }

    public static String sha(Map<String, Object> metadata, String side) {
        String sha = Json.text(Json.map(Json.map(metadata.get(side)).get("commit")), "hash");
        if (!sha.matches("[a-f0-9]{40}"))
            throw new IllegalArgumentException("Expected an immutable Bitbucket commit SHA");
        return sha;
    }

    public static String branch(Map<String, Object> metadata) {
        return Json.text(Json.map(Json.map(metadata.get("destination")).get("branch")), "name");
    }

    public static long id(Map<String, Object> value) {
        Object id = value.get("id");
        if (!(id instanceof Integer || id instanceof Long) || ((Number) id).longValue() < 1)
            throw new IllegalArgumentException("Expected a positive Bitbucket PR/comment ID");
        return ((Number) id).longValue();
    }

    @Override
    public boolean isCurrent(ChangeSnapshot snapshot) throws Exception {
        if (pinned == null || !pinned.equals(snapshot))
            throw new IllegalArgumentException("No matching pinned snapshot");
        return sameVersion(pinnedMetadata, metadata());
    }

    private static boolean sameVersion(Map<String, Object> before, Map<String, Object> after) {
        return sha(before, "source").equals(sha(after, "source"))
                && sha(before, "destination").equals(sha(after, "destination"))
                && branch(before).equals(branch(after))
                && Objects.equals(before.get("state"), after.get("state"))
                && Objects.equals(before.get("draft"), after.get("draft"));
    }

    private static int count(Map<String, Object> file, String key) throws IOException {
        Object raw = file.get(key);
        if (!(raw instanceof Number value)
                || value.longValue() < 0
                || value.longValue() > Integer.MAX_VALUE)
            throw new IOException("Missing or invalid Bitbucket diffstat line count");
        return value.intValue();
    }

    private static String patch(String diff) {
        if (diff.lines().filter(line -> line.startsWith("diff --git ")).count() > 1) return "";
        int start = diff.startsWith("@@ ") ? 0 : diff.indexOf("\n@@ ");
        return start < 0 ? "" : diff.substring(start == 0 ? 0 : start + 1);
    }

    static boolean completePatch(String patch, int additions, int deletions) {
        int oldRemaining = 0, newRemaining = 0, added = 0, removed = 0;
        boolean hunk = false;
        for (String line : patch.split("\n")) {
            var matcher = HUNK.matcher(line);
            if (matcher.matches()) {
                if (oldRemaining != 0 || newRemaining != 0) return false;
                hunk = true;
                try {
                    oldRemaining =
                            matcher.group(1) == null ? 1 : Integer.parseInt(matcher.group(1));
                    newRemaining =
                            matcher.group(2) == null ? 1 : Integer.parseInt(matcher.group(2));
                } catch (NumberFormatException invalid) {
                    return false;
                }
            } else if (line.equals("\\ No newline at end of file")) {
                if (!hunk) return false;
            } else {
                if (!hunk || line.isEmpty()) return false;
                switch (line.charAt(0)) {
                    case '+' -> {
                        newRemaining--;
                        added++;
                    }
                    case '-' -> {
                        oldRemaining--;
                        removed++;
                    }
                    case ' ' -> {
                        oldRemaining--;
                        newRemaining--;
                    }
                    default -> {
                        return false;
                    }
                }
                if (oldRemaining < 0 || newRemaining < 0) return false;
            }
        }
        return hunk
                && oldRemaining == 0
                && newRemaining == 0
                && added == additions
                && removed == deletions;
    }

    private static String category(String path) {
        if (path.contains("/generated/") || path.endsWith(".lock")) return "generated";
        if (path.toLowerCase(java.util.Locale.ROOT).contains("test")) return "test";
        if (path.endsWith(".md") || path.endsWith(".txt")) return "documentation";
        if (path.endsWith(".json") || path.endsWith(".yaml") || path.endsWith(".xml"))
            return "configuration";
        return "code";
    }

    @Override
    public void close() {}
}
