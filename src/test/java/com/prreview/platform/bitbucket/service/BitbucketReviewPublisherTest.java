package com.prreview.platform.bitbucket.service;

import static com.prreview.platform.bitbucket.client.BitbucketFixture.*;

import static org.junit.jupiter.api.Assertions.*;

import com.prreview.common.json.Json;
import com.prreview.platform.bitbucket.client.BitbucketFixture;
import com.prreview.platform.github.service.GitHubReviewPublisher;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class BitbucketReviewPublisherTest {
    private static Map<String, Object> report() {
        return Map.of(
                "platform",
                "bitbucket",
                "repository",
                REPO,
                "number",
                1,
                "baseSha",
                BASE,
                "headSha",
                HEAD,
                "baseBranch",
                "main",
                "current",
                true,
                "status",
                "complete",
                "findings",
                List.of(),
                "coverage",
                List.of());
    }

    private static Map<String, Object> comment(long id, String actor, String body) {
        return Map.of("id", id, "user", Map.of("uuid", actor), "content", Map.of("raw", body));
    }

    @Test
    void publishesAndUpdatesOneOwnedSummaryWithNormalizedContent() throws Exception {
        var comments = new ArrayList<Map<String, Object>>();
        var methods = new ArrayList<String>();
        var callbacks = new AtomicInteger();
        try (var fixture =
                new BitbucketFixture(
                        exchange -> {
                            if (exchange.getRequestURI().getPath().contains("/comments")) {
                                if (exchange.getRequestMethod().equals("GET"))
                                    return ok(Map.of("values", comments));
                                methods.add(exchange.getRequestMethod());
                                var payload =
                                        Json.object(
                                                new String(
                                                        exchange.getRequestBody().readAllBytes()));
                                comments.clear();
                                comments.add(
                                        comment(
                                                42,
                                                ACTOR,
                                                Json.text(
                                                        Json.map(payload.get("content")), "raw")));
                                return ok(Map.of("id", 42));
                            }
                            return standard(exchange);
                        })) {
            var publisher = new BitbucketReviewPublisher(fixture.access(true));
            assertEquals(
                    42, publisher.publish(REPO, 1, report(), false, callbacks::incrementAndGet));
            assertEquals(
                    42, publisher.publish(REPO, 1, report(), false, callbacks::incrementAndGet));
            assertEquals(List.of("POST", "PUT"), methods);
            assertEquals(2, callbacks.get());
            assertEquals(1, comments.size());
            assertTrue(
                    Json.text(Json.map(comments.getFirst().get("content")), "raw")
                            .startsWith(BitbucketReviewPublisher.marker(REPO, 1) + "\n"));
            String expected =
                    "Basic "
                            + java.util.Base64.getEncoder()
                                    .encodeToString(
                                            "fixture@example.invalid:publish-token".getBytes());
            assertTrue(fixture.credentials.stream().allMatch(expected::equals));
        }
    }

    @Test
    void rejectsStaleOrWrongReportsBeforeWriting() throws Exception {
        var metadata = new AtomicReference<>(metadata());
        try (var fixture =
                new BitbucketFixture(
                        exchange -> {
                            if (exchange.getRequestURI().getPath().endsWith("/pullrequests/1"))
                                return ok(metadata.get());
                            if (exchange.getRequestURI().getPath().contains("/comments"))
                                return ok(Map.of("values", List.of()));
                            return standard(exchange);
                        })) {
            var publisher = new BitbucketReviewPublisher(fixture.access(true));
            var wrong = new HashMap<>(report());
            wrong.put("platform", "github");
            assertThrows(
                    IllegalArgumentException.class,
                    () -> publisher.publish(REPO, 1, wrong, false, () -> fail("write")));
            metadata.get().put("source", endpoint("c".repeat(40), "feature"));
            assertThrows(
                    GitHubReviewPublisher.Outdated.class,
                    () -> publisher.publish(REPO, 1, report(), false, () -> fail("write")));
            assertTrue(fixture.requests.stream().allMatch(path -> path.startsWith("GET ")));
        }
    }

    @Test
    void reconcilesAnAcceptedWriteWithLostResponseWithoutPostingTwice() throws Exception {
        var comments = new ArrayList<Map<String, Object>>();
        var writes = new AtomicInteger();
        try (var fixture =
                new BitbucketFixture(
                        exchange -> {
                            if (exchange.getRequestURI().getPath().contains("/comments")) {
                                if (exchange.getRequestMethod().equals("GET"))
                                    return ok(Map.of("values", comments));
                                writes.incrementAndGet();
                                var payload =
                                        Json.object(
                                                new String(
                                                        exchange.getRequestBody().readAllBytes()));
                                comments.add(
                                        comment(
                                                42,
                                                ACTOR,
                                                Json.text(
                                                        Json.map(payload.get("content")), "raw")));
                                return new Response(503, Map.of(), null);
                            }
                            return standard(exchange);
                        })) {
            assertEquals(
                    42,
                    new BitbucketReviewPublisher(fixture.access(true))
                            .publish(REPO, 1, report(), false, () -> {}));
            assertEquals(1, writes.get());
        }
    }

    @Test
    void preservesUncertaintyWhenNoCommentCanBeReconciled() throws Exception {
        var writes = new AtomicInteger();
        try (var fixture =
                new BitbucketFixture(
                        exchange -> {
                            if (exchange.getRequestURI().getPath().contains("/comments")) {
                                if (exchange.getRequestMethod().equals("GET"))
                                    return ok(Map.of("values", List.of()));
                                writes.incrementAndGet();
                                return new Response(503, Map.of(), null);
                            }
                            return standard(exchange);
                        })) {
            var publisher = new BitbucketReviewPublisher(fixture.access(true));
            assertThrows(
                    GitHubReviewPublisher.Uncertain.class,
                    () -> publisher.publish(REPO, 1, report(), true, () -> fail("write")));
            assertThrows(
                    GitHubReviewPublisher.Uncertain.class,
                    () -> publisher.publish(REPO, 1, report(), false, () -> {}));
            assertEquals(1, writes.get());
        }
    }

    @Test
    void ignoresForeignAndInlineCommentsAndRejectsDuplicateOwnedSummaries() throws Exception {
        String marker = BitbucketReviewPublisher.marker(REPO, 1) + "\nold";
        var comments = new ArrayList<Map<String, Object>>();
        comments.add(comment(1, "other-actor", marker));
        var inline = new HashMap<>(comment(2, ACTOR, marker));
        inline.put("inline", Map.of("path", "Money.java"));
        comments.add(inline);
        var owned = comment(3, ACTOR, marker);
        comments.add(owned);
        var method = new AtomicReference<String>();
        try (var fixture =
                new BitbucketFixture(
                        exchange -> {
                            if (exchange.getRequestURI().getPath().contains("/comments")) {
                                if (exchange.getRequestMethod().equals("GET"))
                                    return ok(Map.of("values", comments));
                                method.set(
                                        exchange.getRequestMethod()
                                                + " "
                                                + exchange.getRequestURI().getPath());
                                return ok(Map.of("id", 3));
                            }
                            return standard(exchange);
                        })) {
            var publisher = new BitbucketReviewPublisher(fixture.access(true));
            assertEquals(3, publisher.publish(REPO, 1, report(), false, () -> {}));
            assertTrue(method.get().endsWith("/comments/3"));
            assertTrue(method.get().startsWith("PUT "));
            comments.add(comment(4, ACTOR, marker));
            assertThrows(
                    IOException.class,
                    () -> publisher.publish(REPO, 1, report(), false, () -> fail("write")));
        }
    }

    @Test
    void paginatesOwnedCommentsAndRefusesForeignNextLinks() throws Exception {
        var foreignNext = new AtomicBoolean();
        try (var fixture =
                new BitbucketFixture(
                        exchange -> {
                            if (exchange.getRequestURI().getPath().contains("/comments")) {
                                if (!exchange.getRequestMethod().equals("GET"))
                                    return ok(Map.of("id", 42));
                                if (exchange.getRequestURI().getQuery().contains("page=1"))
                                    return ok(
                                            Map.of(
                                                    "values",
                                                    List.of(),
                                                    "next",
                                                    foreignNext.get()
                                                            ? "https://attacker.invalid/steal"
                                                            : ROOT
                                                                    + "/pullrequests/1/comments?pagelen=100&page=2"));
                                return ok(
                                        Map.of(
                                                "values",
                                                List.of(
                                                        comment(
                                                                42,
                                                                ACTOR,
                                                                BitbucketReviewPublisher.marker(
                                                                                REPO, 1)
                                                                        + "\nold"))));
                            }
                            return standard(exchange);
                        })) {
            var publisher = new BitbucketReviewPublisher(fixture.access(true));
            assertEquals(42, publisher.publish(REPO, 1, report(), true, () -> {}));
            foreignNext.set(true);
            assertThrows(
                    SecurityException.class,
                    () -> publisher.publish(REPO, 1, report(), false, () -> fail("write")));
        }
    }

    @Test
    void marksCommentOutdatedIfHeadChangesDuringPublication() throws Exception {
        var written = new AtomicBoolean();
        var writes = new ArrayList<String>();
        try (var fixture =
                new BitbucketFixture(
                        exchange -> {
                            String path = exchange.getRequestURI().getPath();
                            if (path.contains("/comments")) {
                                if (exchange.getRequestMethod().equals("GET"))
                                    return ok(Map.of("values", List.of()));
                                written.set(true);
                                writes.add(new String(exchange.getRequestBody().readAllBytes()));
                                return ok(Map.of("id", 42));
                            }
                            if (path.endsWith("/pullrequests/1") && written.get()) {
                                var metadata = metadata();
                                metadata.put("source", endpoint("c".repeat(40), "feature"));
                                return ok(metadata);
                            }
                            return standard(exchange);
                        })) {
            assertThrows(
                    GitHubReviewPublisher.Outdated.class,
                    () ->
                            new BitbucketReviewPublisher(fixture.access(true))
                                    .publish(REPO, 1, report(), false, () -> {}));
            assertEquals(2, writes.size());
            assertTrue(writes.getLast().contains("Outdated POC review"));
        }
    }

    @Test
    void definitiveWriteRejectionIsNotRetriedAndFailedPostWriteValidationIsUncertain()
            throws Exception {
        for (boolean rejectedWrite : List.of(true, false)) {
            var written = new AtomicBoolean();
            var writes = new AtomicInteger();
            try (var fixture =
                    new BitbucketFixture(
                            exchange -> {
                                String path = exchange.getRequestURI().getPath();
                                if (path.contains("/comments")) {
                                    if (exchange.getRequestMethod().equals("GET"))
                                        return ok(Map.of("values", List.of()));
                                    written.set(true);
                                    writes.incrementAndGet();
                                    return rejectedWrite
                                            ? new Response(403, Map.of(), null)
                                            : ok(Map.of("id", 42));
                                }
                                if (path.endsWith("/pullrequests/1") && written.get())
                                    return new Response(403, Map.of(), null);
                                return standard(exchange);
                            })) {
                var publisher = new BitbucketReviewPublisher(fixture.access(true));
                if (rejectedWrite)
                    assertThrows(
                            com.prreview.platform.bitbucket.client.BitbucketApiClient
                                    .RequestException.class,
                            () -> publisher.publish(REPO, 1, report(), false, () -> {}));
                else
                    assertThrows(
                            GitHubReviewPublisher.Uncertain.class,
                            () -> publisher.publish(REPO, 1, report(), false, () -> {}));
                assertEquals(1, writes.get());
            }
        }
    }
}
