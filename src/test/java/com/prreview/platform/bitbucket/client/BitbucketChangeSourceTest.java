package com.prreview.platform.bitbucket.client;

import static com.prreview.platform.bitbucket.client.BitbucketFixture.*;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

class BitbucketChangeSourceTest {
    @Test
    void resolvesAbbreviatedHashesFromRealPullRequestResponsesBeforePinning() throws Exception {
        try (var fixture =
                new BitbucketFixture(
                        exchange -> {
                            String path = exchange.getRequestURI().getPath();
                            if (path.contains("/commit/"))
                                return ok(
                                        Map.of(
                                                "hash",
                                                path.endsWith(HEAD.substring(0, 12))
                                                        ? HEAD
                                                        : BASE));
                            if (path.endsWith("/pullrequests/1")) {
                                var metadata = metadata();
                                metadata.put("source", endpoint(HEAD.substring(0, 12), "feature"));
                                metadata.put(
                                        "destination", endpoint(BASE.substring(0, 12), "main"));
                                return ok(metadata);
                            }
                            return standard(exchange);
                        })) {
            var source = fixture.source();
            var snapshot = source.snapshot();
            assertEquals(HEAD, snapshot.headSha());
            assertEquals(BASE, snapshot.baseSha());
            assertTrue(source.isCurrent(snapshot));
            assertEquals("return amount * 100;", source.fileAtCommit(HEAD, "Money.java"));
            assertTrue(
                    fixture.requests.stream()
                            .anyMatch(
                                    request ->
                                            request.endsWith("/commit/" + HEAD.substring(0, 12))));
        }
        try (var fixture =
                new BitbucketFixture(
                        exchange -> {
                            if (exchange.getRequestURI().getPath().contains("/commit/"))
                                return ok(Map.of("hash", "c".repeat(40)));
                            var metadata = metadata();
                            metadata.put("source", endpoint(HEAD.substring(0, 12), "feature"));
                            return ok(metadata);
                        })) {
            assertThrows(SecurityException.class, () -> fixture.source().snapshot());
        }
    }

    @Test
    void pinsSnapshotAndOnlyReadsChangedTextAtItsImmutableHead() throws Exception {
        try (var fixture = new BitbucketFixture(BitbucketFixture::standard)) {
            var source = fixture.source();
            var snapshot = source.snapshot();
            assertEquals("bitbucket", snapshot.platform());
            assertEquals(BASE, snapshot.baseSha());
            assertEquals(HEAD, snapshot.headSha());
            assertEquals("main", snapshot.baseBranch());
            assertEquals("open", snapshot.state());
            assertTrue(snapshot.enumerationComplete());
            assertTrue(snapshot.files().getFirst().patchComplete());
            assertEquals(PATCH, snapshot.files().getFirst().patch());
            assertEquals("return amount * 100;", source.fileAtCommit(HEAD, "Money.java"));
            assertTrue(source.isCurrent(snapshot));
            assertThrows(
                    IllegalArgumentException.class, () -> source.fileAtCommit(BASE, "Money.java"));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> source.fileAtCommit(HEAD, "not-changed.java"));
            assertThrows(
                    IllegalArgumentException.class, () -> source.fileAtCommit(HEAD, "../secret"));
            assertTrue(
                    fixture.requests.stream()
                            .anyMatch(
                                    path ->
                                            path.contains(
                                                    "/diff/"
                                                            + HEAD
                                                            + ".."
                                                            + BASE
                                                            + "?topic=true")));
            String expected =
                    "Basic "
                            + Base64.getEncoder()
                                    .encodeToString(
                                            "fixture@example.invalid:read-token".getBytes());
            assertTrue(fixture.credentials.stream().allMatch(expected::equals));
        }
    }

    @Test
    void followsPaginationAndEncodesRenamePathsWithoutReadingRemovedFiles() throws Exception {
        try (var fixture =
                new BitbucketFixture(
                        exchange -> {
                            String path = exchange.getRequestURI().toString();
                            if (path.contains("/diffstat/")) {
                                if (path.endsWith("page=1")) {
                                    var files = new ArrayList<Map<String, Object>>();
                                    for (int i = 0; i < 100; i++)
                                        files.add(file("File" + i + ".java"));
                                    return ok(
                                            Map.of(
                                                    "size",
                                                    102,
                                                    "values",
                                                    files,
                                                    "next",
                                                    ROOT
                                                            + "/diffstat/"
                                                            + HEAD
                                                            + ".."
                                                            + BASE
                                                            + "?topic=true&pagelen=100&page=2"));
                                }
                                var renamed = new HashMap<>(file("src/new name.java"));
                                renamed.put("status", "renamed");
                                var removed = new HashMap<>(file("old.java"));
                                removed.put("status", "removed");
                                removed.put("new", null);
                                return ok(Map.of("size", 102, "values", List.of(renamed, removed)));
                            }
                            return standard(exchange);
                        })) {
            var source = fixture.source();
            var snapshot = source.snapshot();
            assertTrue(snapshot.enumerationComplete());
            assertEquals(102, snapshot.files().size());
            assertEquals("renamed", snapshot.files().get(100).status());
            assertEquals("removed", snapshot.files().getLast().status());
            assertEquals("return amount * 100;", source.fileAtCommit(HEAD, "src/new name.java"));
            assertTrue(
                    fixture.requests.stream()
                            .anyMatch(
                                    path ->
                                            path.contains(
                                                    "/src/" + HEAD + "/src/new%20name.java")));
            assertThrows(
                    IllegalArgumentException.class, () -> source.fileAtCommit(HEAD, "old.java"));
        }
    }

    @Test
    void reportsBinaryOmittedAndTruncatedDiffsAsPartial() throws Exception {
        for (String diff :
                List.of(
                        "Binary files differ",
                        "",
                        "@@ -1,2 +1,2 @@\n-old\n+new\n",
                        PATCH + "+extra\n")) {
            try (var fixture =
                    new BitbucketFixture(
                            exchange ->
                                    exchange.getRequestURI().getPath().contains("/diff/")
                                            ? ok(diff)
                                            : standard(exchange))) {
                assertFalse(fixture.source().snapshot().files().getFirst().patchComplete());
            }
        }
        try (var fixture =
                new BitbucketFixture(
                        exchange ->
                                exchange.getRequestURI().getPath().contains("/diff/")
                                        ? new Response(555, Map.of(), null)
                                        : standard(exchange))) {
            assertFalse(fixture.source().snapshot().files().getFirst().patchComplete());
        }
    }

    @Test
    void detectsMovingHeadsAndReflectsDraftAndClosedState() throws Exception {
        var reads = new AtomicInteger();
        try (var fixture =
                new BitbucketFixture(
                        exchange -> {
                            if (exchange.getRequestURI().getPath().endsWith("/pullrequests/1")) {
                                var metadata = metadata();
                                if (reads.incrementAndGet() > 1)
                                    metadata.put("source", endpoint("c".repeat(40), "feature"));
                                return ok(metadata);
                            }
                            return standard(exchange);
                        })) {
            assertThrows(IOException.class, () -> fixture.source().snapshot());
        }
        try (var fixture =
                new BitbucketFixture(
                        exchange -> {
                            if (exchange.getRequestURI().getPath().endsWith("/pullrequests/1")) {
                                var metadata = metadata();
                                metadata.put("draft", true);
                                metadata.put("state", "DECLINED");
                                return ok(metadata);
                            }
                            return standard(exchange);
                        })) {
            var snapshot = fixture.source().snapshot();
            assertTrue(snapshot.draft());
            assertEquals("declined", snapshot.state());
        }
    }

    @Test
    void rejectsCrossRepositoryForksAndInvalidCommitRefs() throws Exception {
        for (boolean fork : List.of(true, false)) {
            try (var fixture =
                    new BitbucketFixture(
                            exchange -> {
                                var metadata = metadata();
                                var source =
                                        new HashMap<>(endpoint(fork ? HEAD : "main", "feature"));
                                if (fork)
                                    source.put("repository", Map.of("full_name", "other/fork"));
                                metadata.put("source", source);
                                return ok(metadata);
                            })) {
                if (fork) assertThrows(SecurityException.class, () -> fixture.source().snapshot());
                else
                    assertThrows(IllegalArgumentException.class, () -> fixture.source().snapshot());
            }
        }
    }

    @Test
    void rejectsForeignPaginationAndRepeatedFilesAndPreservesSizeMismatch() throws Exception {
        for (String next :
                List.of(
                        "https://attacker.invalid/steal",
                        ROOT + "/diffstat/" + HEAD + ".." + BASE + "?topic=false&page=2")) {
            try (var fixture =
                    new BitbucketFixture(
                            exchange ->
                                    exchange.getRequestURI().getPath().contains("/diffstat/")
                                            ? ok(
                                                    Map.of(
                                                            "values",
                                                            List.of(file("Money.java")),
                                                            "next",
                                                            next))
                                            : standard(exchange))) {
                assertThrows(SecurityException.class, () -> fixture.source().snapshot());
            }
        }
        try (var fixture =
                new BitbucketFixture(
                        exchange ->
                                exchange.getRequestURI().getPath().contains("/diffstat/")
                                        ? ok(
                                                Map.of(
                                                        "values",
                                                        List.of(
                                                                file("Money.java"),
                                                                file("Money.java"))))
                                        : standard(exchange))) {
            assertThrows(IOException.class, () -> fixture.source().snapshot());
        }
        try (var fixture =
                new BitbucketFixture(
                        exchange ->
                                exchange.getRequestURI().getPath().contains("/diffstat/")
                                        ? ok(
                                                Map.of(
                                                        "size",
                                                        2,
                                                        "values",
                                                        List.of(file("Money.java"))))
                                        : standard(exchange))) {
            assertFalse(fixture.source().snapshot().enumerationComplete());
        }
    }

    @Test
    void rejectsBinaryAndOversizedContext() throws Exception {
        for (String content : List.of("binary\0data", "x".repeat(128_001))) {
            try (var fixture =
                    new BitbucketFixture(
                            exchange ->
                                    exchange.getRequestURI().getPath().contains("/src/")
                                            ? ok(content)
                                            : standard(exchange))) {
                var source = fixture.source();
                source.snapshot();
                assertThrows(IOException.class, () -> source.fileAtCommit(HEAD, "Money.java"));
            }
        }
    }

    @Test
    void deniesWritesAndCredentialRedirectsAndDoesNotRetryRateLimits() throws Exception {
        try (var fixture =
                new BitbucketFixture(
                        exchange -> new Response(429, Map.of("secret", "never in error"), null))) {
            var read = fixture.access(false);
            assertThrows(
                    SecurityException.class,
                    () ->
                            read.request(
                                    "POST",
                                    "repositories/" + REPO + "/pullrequests/1/comments",
                                    Map.of()));
            var error =
                    assertThrows(
                            BitbucketApiClient.RequestException.class,
                            () -> read.get("repositories/" + REPO + "/pullrequests/1"));
            assertEquals(429, error.status());
            assertFalse(error.getMessage().contains("never in error"));
            assertEquals(1, fixture.requests.size());
            assertThrows(
                    SecurityException.class,
                    () -> read.get("repositories/other/repo/pullrequests/1"));
            assertThrows(
                    SecurityException.class,
                    () -> read.get("repositories/" + REPO + "/%2e%2e/secret"));
        }
        try (var fixture =
                new BitbucketFixture(exchange -> redirect("https://attacker.invalid/steal"))) {
            assertThrows(
                    SecurityException.class,
                    () ->
                            fixture.access(false)
                                    .get("repositories/" + REPO + "/pullrequests/1/diff"));
            assertEquals(1, fixture.requests.size());
        }
        try (var fixture =
                new BitbucketFixture(exchange -> redirect(ROOT + "/src/main/Money.java"))) {
            assertThrows(
                    SecurityException.class,
                    () ->
                            fixture.access(false)
                                    .text(
                                            "repositories/" + REPO + "/src/" + HEAD + "/Money.java",
                                            128_000));
            assertEquals(1, fixture.requests.size());
        }
        try (var fixture =
                new BitbucketFixture(
                        exchange ->
                                exchange.getRequestURI().getPath().endsWith("/pullrequests/1/diff")
                                        ? redirect(ROOT + "/diff/" + HEAD + ".." + BASE)
                                        : ok(DIFF))) {
            assertEquals(
                    DIFF,
                    fixture.access(false)
                            .text("repositories/" + REPO + "/pullrequests/1/diff", 2_000_000));
            assertEquals(2, fixture.requests.size());
        }
    }

    @Test
    void paginationLimitNeverClaimsCompleteEnumeration() throws Exception {
        var pages = new AtomicInteger();
        try (var fixture =
                new BitbucketFixture(
                        exchange -> {
                            if (exchange.getRequestURI().getPath().contains("/diffstat/")) {
                                int page = pages.incrementAndGet();
                                return ok(
                                        Map.of(
                                                "values",
                                                List.of(),
                                                "next",
                                                ROOT
                                                        + "/diffstat/"
                                                        + HEAD
                                                        + ".."
                                                        + BASE
                                                        + "?topic=true&pagelen=100&page="
                                                        + (page + 1)));
                            }
                            return standard(exchange);
                        })) {
            assertFalse(fixture.source().snapshot().enumerationComplete());
            assertEquals(30, pages.get());
        }
    }
}
