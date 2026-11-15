package com.prreview.platform.github.client;

import static org.junit.jupiter.api.Assertions.*;

import com.prreview.common.json.Json;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class GitHubChangeSourceTest {
    private HttpServer server;

    @AfterEach
    void close() {
        if (server != null) server.stop(0);
    }

    @Test
    void paginates101FilesPinsShaAndReadsForkContent() throws Exception {
        var paths = new ArrayList<String>();
        var api =
                start(
                        path -> {
                            paths.add(path);
                            if (path.contains("/contents/")) {
                                return Map.of(
                                        "type",
                                        "file",
                                        "encoding",
                                        "base64",
                                        "size",
                                        5,
                                        "content",
                                        Base64.getEncoder()
                                                .encodeToString(
                                                        "hello".getBytes(StandardCharsets.UTF_8)));
                            }
                            if (path.contains("/files?")) {
                                int count = path.endsWith("page=1") ? 100 : 1;
                                var files = new ArrayList<Map<String, Object>>();
                                for (int i = 0; i < count; i++)
                                    files.add(
                                            Map.of(
                                                    "filename",
                                                    path.endsWith("page=1")
                                                            ? "File" + i + ".java"
                                                            : "last.java",
                                                    "status",
                                                    "modified",
                                                    "patch",
                                                    "@@ -1 +1 @@\n-old\n+new",
                                                    "additions",
                                                    1,
                                                    "deletions",
                                                    1));
                                return files;
                            }
                            return metadata("b".repeat(40), 101);
                        });
        var snapshot = api.snapshot();
        assertTrue(snapshot.enumerationComplete());
        assertEquals(101, snapshot.files().size());
        assertTrue(snapshot.files().stream().allMatch(file -> file.patchComplete()));
        assertEquals("hello", api.fileAtCommit(snapshot.headSha(), "src/File.java"));
        assertTrue(
                paths.stream()
                        .anyMatch(
                                path ->
                                        path.equals(
                                                "/repos/fork/repo/contents/src/File.java?ref="
                                                        + "b".repeat(40))));
        assertThrows(
                IllegalArgumentException.class,
                () -> api.fileAtCommit("a".repeat(40), "src/File.java"));
        assertThrows(
                IllegalArgumentException.class,
                () -> api.fileAtCommit(snapshot.headSha(), "../secret"));
    }

    @Test
    void rejectsSnapshotChangingDuringPagination() throws Exception {
        var calls = new AtomicInteger();
        var api =
                start(
                        path ->
                                path.contains("/files?")
                                        ? List.of()
                                        : metadata(
                                                (calls.incrementAndGet() == 1 ? "b" : "c")
                                                        .repeat(40),
                                                0));
        assertThrows(java.io.IOException.class, api::snapshot);
    }

    @Test
    void enforcesDecodedSizeEvenIfMetadataUnderstatesIt() throws Exception {
        var api =
                start(
                        path -> {
                            if (path.contains("/contents/"))
                                return Map.of(
                                        "type",
                                        "file",
                                        "encoding",
                                        "base64",
                                        "size",
                                        5,
                                        "content",
                                        Base64.getEncoder().encodeToString(new byte[128_001]));
                            return path.contains("/files?")
                                    ? List.of()
                                    : metadata("b".repeat(40), 0);
                        });
        var snapshot = api.snapshot();
        assertTrue(
                assertThrows(
                                java.io.IOException.class,
                                () -> api.fileAtCommit(snapshot.headSha(), "large.java"))
                        .getMessage()
                        .contains("Decoded file exceeds"));
    }

    @Test
    void exposesMissingOrTruncatedPatchesAndEnumerationMismatch() throws Exception {
        var api =
                start(
                        path ->
                                path.contains("/files?")
                                        ? List.of(
                                                Map.of(
                                                        "filename",
                                                        "large.java",
                                                        "status",
                                                        "modified",
                                                        "patch",
                                                        "@@ -1 +1 @@\n+only one",
                                                        "additions",
                                                        20,
                                                        "deletions",
                                                        5))
                                        : metadata("b".repeat(40), 3));
        var snapshot = api.snapshot();
        assertFalse(snapshot.enumerationComplete());
        assertFalse(snapshot.files().getFirst().patchComplete());
    }

    @Test
    void rejectsTraversalAndInvalidRepositoryNames() {
        for (String path : List.of("../secret", "/absolute", "a/../b", "a//b", "a\\b", "a/./b")) {
            assertThrows(IllegalArgumentException.class, () -> GitHubChangeSource.encodePath(path));
        }
        assertThrows(
                IllegalArgumentException.class, () -> new GitHubChangeSource("owner/../repo", 1));
        assertThrows(IllegalArgumentException.class, () -> new GitHubChangeSource("owner/repo", 0));
        assertEquals("src/My%20File.java", GitHubChangeSource.encodePath("src/My File.java"));
    }

    @Test
    void draftAndTargetBranchChangesInvalidateTheSnapshot() throws Exception {
        var draft = new java.util.concurrent.atomic.AtomicBoolean();
        var branch = new java.util.concurrent.atomic.AtomicReference<>("main");
        var api =
                start(
                        path -> {
                            if (path.contains("/files?")) return List.of();
                            var values = new java.util.HashMap<>(metadata("b".repeat(40), 0));
                            values.put("draft", draft.get());
                            values.put("base", Map.of("sha", "a".repeat(40), "ref", branch.get()));
                            return values;
                        });
        var snapshot = api.snapshot();
        assertFalse(snapshot.draft());
        assertEquals("main", snapshot.baseBranch());
        draft.set(true);
        assertFalse(api.isCurrent());
        draft.set(false);
        branch.set("other");
        assertFalse(api.isCurrent());
    }

    @Test
    void rejectsRepeatedPaginationRatherThanClaimingFullCoverage() throws Exception {
        var repeated = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 100; i++)
            repeated.add(
                    Map.of(
                            "filename",
                            "File" + i,
                            "status",
                            "modified",
                            "patch",
                            "@@ -1 +1 @@\n-old\n+new",
                            "additions",
                            1,
                            "deletions",
                            1));
        var api =
                start(path -> path.contains("/files?") ? repeated : metadata("b".repeat(40), 200));
        var error = assertThrows(java.io.IOException.class, api::snapshot);
        assertTrue(error.getMessage().contains("Repeated file"));
    }

    @Test
    void stopsAtRateLimitWithoutUnboundedRetries() throws Exception {
        var calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    calls.incrementAndGet();
                    exchange.sendResponseHeaders(403, -1);
                    exchange.close();
                });
        server.start();
        var api =
                new GitHubChangeSource(
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"),
                        "owner/repo",
                        1);
        assertTrue(
                assertThrows(java.io.IOException.class, api::snapshot)
                        .getMessage()
                        .contains("HTTP 403"));
        assertEquals(1, calls.get());
    }

    @FunctionalInterface
    private interface Response {
        Object get(String path);
    }

    private GitHubChangeSource start(Response response) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    byte[] body =
                            Json.write(response.get(exchange.getRequestURI().toString()))
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(body);
                    }
                });
        server.start();
        return new GitHubChangeSource(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"),
                "owner/repo",
                1);
    }

    private static Map<String, Object> metadata(String head, int count) {
        return Map.of(
                "head",
                Map.of("sha", head, "repo", Map.of("full_name", "fork/repo")),
                "base",
                Map.of("sha", "a".repeat(40)),
                "changed_files",
                count,
                "title",
                "Change",
                "body",
                "",
                "state",
                "open");
    }
}
