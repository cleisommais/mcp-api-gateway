package com.prreview.platform.gitlab.client;

import static com.prreview.platform.gitlab.client.GitLabFixture.*;

import static org.junit.jupiter.api.Assertions.*;

import com.prreview.common.json.Json;
import com.prreview.mcp.client.StdioMcpClient;
import com.prreview.mcpgateway.config.ReviewToolCatalog;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class GitLabChangeSourceTest {
    @Test
    void paginates101FilesAndReadsRenamedForkContentAtImmutableHead() throws Exception {
        try (var fixture =
                new GitLabFixture(
                        exchange -> {
                            String uri = exchange.getRequestURI().toString();
                            if (uri.contains("/repository/files/"))
                                return ok(
                                        Map.of(
                                                "encoding",
                                                "base64",
                                                "size",
                                                5,
                                                "content",
                                                Base64.getEncoder()
                                                        .encodeToString(
                                                                "hello"
                                                                        .getBytes(
                                                                                StandardCharsets
                                                                                        .UTF_8)),
                                                "commit_id",
                                                HEAD,
                                                "file_path",
                                                "src/new name.java"));
                            if (uri.contains("/diffs?")) {
                                var files = new ArrayList<Map<String, Object>>();
                                if (uri.endsWith("page=1"))
                                    for (int i = 0; i < 100; i++)
                                        files.add(file("File" + i + ".java"));
                                else {
                                    var renamed = new HashMap<>(file("src/new name.java"));
                                    renamed.put("old_path", "old.java");
                                    renamed.put("renamed_file", true);
                                    files.add(renamed);
                                }
                                return ok(files);
                            }
                            return ok(metadata("101"));
                        })) {
            var api = fixture.api();
            var snapshot = api.snapshot();
            assertTrue(snapshot.enumerationComplete());
            assertEquals(101, snapshot.files().size());
            assertTrue(
                    snapshot.files().stream()
                            .allMatch(
                                    f ->
                                            f.patchComplete()
                                                    && f.additions() == 1
                                                    && f.deletions() == 1));
            assertEquals("renamed", snapshot.files().getLast().status());
            assertEquals("43", snapshot.headRepository());
            assertEquals("hello", api.fileAtCommit(HEAD, "src/new name.java"));
            assertTrue(
                    fixture.requests.contains(
                            "GET /projects/43/repository/files/src%2Fnew%20name.java?ref=" + HEAD));
            assertTrue(api.isCurrent(snapshot));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> api.fileAtCommit(BASE, "src/new name.java"));
            assertThrows(IllegalArgumentException.class, () -> api.fileAtCommit(HEAD, "../secret"));
            assertTrue(fixture.tokens.stream().allMatch("test-read-token"::equals));
        }
    }

    @Test
    void reportsCappedCountsOmittedDiffsAndMalformedHunksAsPartial() throws Exception {
        for (String count : List.of("1000+", "", "4")) {
            try (var fixture =
                    new GitLabFixture(
                            exchange -> {
                                if (!exchange.getRequestURI().toString().contains("/diffs?"))
                                    return ok(metadata(count));
                                var collapsed = new HashMap<>(file("collapsed.java"));
                                collapsed.put("collapsed", true);
                                var large = new HashMap<>(file("large.java"));
                                large.put("too_large", true);
                                var truncated = new HashMap<>(file("truncated.java"));
                                truncated.put("diff", "@@ -1,2 +1,2 @@\n-old\n+new");
                                return ok(List.of(collapsed, large, truncated));
                            })) {
                var snapshot = fixture.api().snapshot();
                assertFalse(snapshot.enumerationComplete());
                assertTrue(snapshot.files().stream().noneMatch(f -> f.patchComplete()));
            }
        }
    }

    @Test
    void mapsAddDeleteRenameAndGeneratedPolicy() throws Exception {
        try (var fixture =
                new GitLabFixture(
                        exchange -> {
                            if (!exchange.getRequestURI().toString().contains("/diffs?"))
                                return ok(metadata("3"));
                            var added = new HashMap<>(file("new.java"));
                            added.put("new_file", true);
                            var removed = new HashMap<>(file("deleted.java"));
                            removed.put("deleted_file", true);
                            removed.put("new_path", "ignored.java");
                            var generated = new HashMap<>(file("generated.java"));
                            generated.put("generated_file", true);
                            return ok(List.of(added, removed, generated));
                        })) {
            var snapshot = fixture.api().snapshot();
            assertEquals("added", snapshot.files().get(0).status());
            assertEquals("removed", snapshot.files().get(1).status());
            assertEquals("deleted.java", snapshot.files().get(1).path());
            assertEquals("generated", snapshot.files().get(2).category());
        }
    }

    @Test
    void refusesRepeatedPagesAndSnapshotChangesWhileEnumerating() throws Exception {
        try (var fixture =
                new GitLabFixture(
                        exchange ->
                                exchange.getRequestURI().toString().contains("/diffs?")
                                        ? ok(
                                                java.util.stream.IntStream.range(0, 100)
                                                        .mapToObj(i -> file("File" + i))
                                                        .toList())
                                        : ok(metadata("200")))) {
            assertTrue(
                    assertThrows(IOException.class, () -> fixture.api().snapshot())
                            .getMessage()
                            .contains("Repeated file"));
        }
        var calls = new AtomicInteger();
        try (var fixture =
                new GitLabFixture(
                        exchange -> {
                            if (exchange.getRequestURI().toString().contains("/diffs?"))
                                return ok(List.of());
                            var result = metadata("0");
                            if (calls.incrementAndGet() > 1) result.put("target_branch", "other");
                            return ok(result);
                        })) {
            assertThrows(IOException.class, () -> fixture.api().snapshot());
        }
    }

    @Test
    void headBaseStartDraftStateBranchAndSourceProjectChangesInvalidateSnapshot() throws Exception {
        for (String field :
                List.of(
                        "head_sha",
                        "base_sha",
                        "start_sha",
                        "draft",
                        "state",
                        "target_branch",
                        "source_project_id")) {
            var current = new AtomicReference<>(metadata("0"));
            try (var fixture =
                    new GitLabFixture(
                            exchange ->
                                    exchange.getRequestURI().toString().contains("/diffs?")
                                            ? ok(List.of())
                                            : ok(current.get()))) {
                var api = fixture.api();
                var snapshot = api.snapshot();
                var changed = metadata("0");
                if (field.endsWith("_sha")) {
                    var refs = new HashMap<>(Json.map(changed.get("diff_refs")));
                    refs.put(field, "c".repeat(40));
                    changed.put("diff_refs", refs);
                    if (field.equals("head_sha")) changed.put("sha", "c".repeat(40));
                } else
                    changed.put(
                            field,
                            switch (field) {
                                case "draft" -> true;
                                case "source_project_id" -> 99;
                                default -> "changed";
                            });
                current.set(changed);
                assertFalse(api.isCurrent(snapshot), field);
            }
        }
    }

    @Test
    void refusesUnavailableOrStaleDiffRefsAndWrongResource() throws Exception {
        for (String field : List.of("diff_refs", "sha", "iid", "source_project_id")) {
            try (var fixture =
                    new GitLabFixture(
                            exchange -> {
                                var result = metadata("0");
                                result.put(
                                        field,
                                        field.equals("diff_refs")
                                                ? Map.of()
                                                : field.equals("sha") ? BASE : 0);
                                return ok(result);
                            })) {
                assertThrows(Exception.class, () -> fixture.api().snapshot());
            }
        }
    }

    @Test
    void boundsDecodedContentAndChecksReturnedCommitAndPath() throws Exception {
        for (String failure : List.of("decoded-size", "binary", "commit", "path")) {
            try (var fixture =
                    new GitLabFixture(
                            exchange -> {
                                String path = exchange.getRequestURI().toString();
                                if (path.contains("/repository/files/"))
                                    return ok(
                                            Map.of(
                                                    "encoding",
                                                    "base64",
                                                    "size",
                                                    5,
                                                    "content",
                                                    Base64.getEncoder()
                                                            .encodeToString(
                                                                    failure.equals("decoded-size")
                                                                            ? new byte[128_001]
                                                                            : failure.equals(
                                                                                            "binary")
                                                                                    ? new byte[] {0}
                                                                                    : "hello"
                                                                                            .getBytes(
                                                                                                    StandardCharsets
                                                                                                            .UTF_8)),
                                                    "commit_id",
                                                    failure.equals("commit") ? BASE : HEAD,
                                                    "file_path",
                                                    failure.equals("path")
                                                            ? "other.java"
                                                            : "File.java"));
                                return path.contains("/diffs?") ? ok(List.of()) : ok(metadata("0"));
                            })) {
                var api = fixture.api();
                api.snapshot();
                assertThrows(IOException.class, () -> api.fileAtCommit(HEAD, "File.java"));
            }
        }
    }

    @Test
    void stopsAtPermissionRateLimitAndRedirectErrorsWithoutLeakingBodyOrRetrying()
            throws Exception {
        for (int status : List.of(301, 401, 403, 404, 429, 500)) {
            try (var fixture =
                    new GitLabFixture(
                            exchange -> new Response(status, Map.of("secret", "do-not-log")))) {
                var error =
                        assertThrows(
                                GitLabApiClient.RequestException.class,
                                () -> fixture.api().snapshot());
                assertEquals(status, error.status());
                assertFalse(error.getMessage().contains("do-not-log"));
                assertEquals(1, fixture.requests.size());
            }
        }
    }

    @Test
    void validatesRealGitLabMcpCatalogWithoutNetworkOrCredentials() {
        try (var source = new StdioMcpClient("gitlab", REPO, 1, Map.of())) {
            ReviewToolCatalog.validate(source.catalog());
            assertEquals("gitlab-review", source.initialized().serverInfo().name());
            assertEquals("0.1.0", source.initialized().serverInfo().version());
            assertEquals("2025-11-25", source.initialized().protocolVersion());
        }
    }

    @Test
    void rejectsTraversalAndRejectsWritesFromReadCredential() throws Exception {
        for (String path : List.of("../secret", "/absolute", "a//b", "a\\b", "a/./b"))
            assertThrows(IllegalArgumentException.class, () -> GitLabApiClient.validatePath(path));
        for (String path : List.of("group/../repo", "https://gitlab.com/group/repo", "group//repo"))
            assertThrows(
                    IllegalArgumentException.class, () -> GitLabApiClient.validateRepository(path));
        try (var fixture =
                new GitLabFixture(
                        exchange -> {
                            fail("Write reached server");
                            return null;
                        })) {
            assertThrows(
                    SecurityException.class,
                    () ->
                            fixture.access(false)
                                    .request(
                                            "POST",
                                            "projects/42/merge_requests/1/notes",
                                            Map.of("body", "no")));
            assertEquals(0, fixture.requests.size());
        }
        assertTrue(
                GitLabChangeSource.completePatch(
                        "@@ -0,0 +1,2 @@\n+one\n+two\n\\ No newline at end of file\n"));
        assertFalse(GitLabChangeSource.completePatch("@@ -0,0 +1,2 @@\n+one"));
    }
}
