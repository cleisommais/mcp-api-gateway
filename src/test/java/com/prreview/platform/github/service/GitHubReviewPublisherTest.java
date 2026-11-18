package com.prreview.platform.github.service;

import static org.junit.jupiter.api.Assertions.*;

import com.prreview.common.json.Json;
import com.prreview.domain.model.ReviewJob;
import com.prreview.platform.github.client.GitHubApiClient;
import com.prreview.support.GitHubFixture;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.*;

public class GitHubReviewPublisherTest {
    static ReviewJob job() {
        return new ReviewJob(
                UUID.randomUUID(),
                17,
                GitHubFixture.REPOSITORY,
                1,
                42,
                GitHubFixture.BASE,
                GitHubFixture.HEAD,
                "main",
                "running",
                1,
                null,
                "none",
                null);
    }

    static Map<String, Object> report() {
        return Map.of(
                "status",
                "complete",
                "enumeratedFiles",
                1,
                "model",
                "fixture",
                "coverage",
                List.of(Map.of("status", "analyzed")),
                "findings",
                List.of(
                        Map.of(
                                "file",
                                "Example.java",
                                "line",
                                1,
                                "severity",
                                "MEDIUM",
                                "explanation",
                                "Incorrect result @someone <img> ![x](https://example.invalid)")));
    }

    @Test
    void publishesFreshTypedRecordsAsWellAsRecoveredJson() throws Exception {
        var fresh = new LinkedHashMap<String, Object>(report());
        fresh.put(
                "coverage",
                List.of(
                        new com.prreview.domain.model.Coverage(
                                "Example.java", "analyzed", "validated")));
        fresh.put(
                "findings",
                List.of(
                        new com.prreview.domain.model.Finding(
                                "Example.java", 1, "MEDIUM", "Incorrect result", "return 1;")));
        try (var fixture = new Api()) {
            var publisher = new GitHubReviewPublisher(fixture.access());
            assertEquals(77L, publisher.publish(job(), fresh, false));
            assertEquals(77L, publisher.publish(job(), Json.object(Json.write(fresh)), true));
            assertEquals(1, fixture.posts);
            assertEquals(1, fixture.patches);
            assertTrue(
                    fixture.comments
                            .getFirst()
                            .get("body")
                            .toString()
                            .contains("Incorrect result"));
        }
    }

    @Test
    void createsOneBotSummaryAndReusesItOnRetry() throws Exception {
        try (var fixture = new Api()) {
            var publisher = new GitHubReviewPublisher(fixture.access());
            var job = job();
            long id = publisher.publish(job, report(), false);
            assertEquals(id, publisher.publish(job, report(), true));
            assertEquals(1, fixture.posts);
            assertEquals(1, fixture.patches);
            String body = (String) fixture.comments.getFirst().get("body");
            assertTrue(body.contains(job.headSha()));
            assertFalse(body.contains("@someone"));
            assertFalse(body.contains("<img>"));
            assertTrue(body.contains("human review"));
        }
    }

    @Test
    void reconcilesResponseLostAfterCommentCreationWithoutAnotherPost() throws Exception {
        try (var fixture = new Api()) {
            fixture.failAfterCreate = true;
            assertEquals(
                    77,
                    new GitHubReviewPublisher(fixture.access()).publish(job(), report(), false));
            assertEquals(1, fixture.posts);
            assertEquals(1, fixture.comments.size());
        }
    }

    @Test
    void uncertainMissingCommentDoesNotBlindlyRepost() throws Exception {
        try (var fixture = new Api()) {
            assertThrows(
                    GitHubReviewPublisher.Uncertain.class,
                    () ->
                            new GitHubReviewPublisher(fixture.access())
                                    .publish(job(), report(), true));
            assertEquals(0, fixture.posts);
        }
    }

    @Test
    void ignoresAnotherActorsCopiedMarker() throws Exception {
        try (var fixture = new Api()) {
            fixture.comments.add(
                    new HashMap<>(
                            Map.of(
                                    "id",
                                    22,
                                    "user",
                                    Map.of("login", "other-user"),
                                    "body",
                                    "<!-- mcp-review-poc:17:1 -->\nSpoofed")));
            new GitHubReviewPublisher(fixture.access()).publish(job(), report(), false);
            assertEquals(1, fixture.posts);
            assertEquals(0, fixture.patches);
        }
    }

    @Test
    void preventsOldSnapshotAndMarksRaceDuringPublicationOutdated() throws Exception {
        try (var fixture = new Api()) {
            fixture.changed = true;
            assertThrows(
                    GitHubReviewPublisher.Outdated.class,
                    () ->
                            new GitHubReviewPublisher(fixture.access())
                                    .publish(job(), report(), false));
            assertEquals(0, fixture.posts);
            fixture.changed = false;
            fixture.changeAfterWrite = true;
            assertThrows(
                    GitHubReviewPublisher.Outdated.class,
                    () ->
                            new GitHubReviewPublisher(fixture.access())
                                    .publish(job(), report(), false));
            assertEquals(1, fixture.posts);
            assertEquals(1, fixture.patches);
            assertTrue(fixture.comments.getFirst().get("body").toString().contains("Outdated"));
        }
    }

    private static final class Api implements AutoCloseable {
        final HttpServer server;
        final List<Map<String, Object>> comments = new ArrayList<>();
        int posts, patches;
        boolean failAfterCreate, changed, changeAfterWrite;

        Api() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(
                    "/",
                    exchange -> {
                        Object response;
                        int status = 200;
                        String method = exchange.getRequestMethod();
                        if (exchange.getRequestURI().getPath().contains("/pulls/")) {
                            response =
                                    Map.of(
                                            "state",
                                            "open",
                                            "draft",
                                            false,
                                            "head",
                                            Map.of(
                                                    "sha",
                                                    changed ? "c".repeat(40) : GitHubFixture.HEAD),
                                            "base",
                                            Map.of("sha", GitHubFixture.BASE, "ref", "main"));
                        } else if (method.equals("GET")) response = comments;
                        else {
                            var request =
                                    Json.object(
                                            new String(
                                                    exchange.getRequestBody().readAllBytes(),
                                                    java.nio.charset.StandardCharsets.UTF_8));
                            if (method.equals("POST")) {
                                posts++;
                                comments.add(
                                        new HashMap<>(
                                                Map.of(
                                                        "id",
                                                        77L,
                                                        "user",
                                                        Map.of("login", "poc[bot]"),
                                                        "body",
                                                        request.get("body"))));
                                status = failAfterCreate ? 500 : 201;
                                if (changeAfterWrite) changed = true;
                            } else {
                                patches++;
                                comments.getLast().put("body", request.get("body"));
                            }
                            response = Map.of("id", 77);
                        }
                        byte[] bytes =
                                Json.write(response)
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(status, bytes.length);
                        try (var output = exchange.getResponseBody()) {
                            output.write(bytes);
                        }
                    });
            server.start();
        }

        GitHubApiClient access() {
            return new GitHubApiClient(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"),
                    new GitHubApiClient.Credential() {
                        @Override
                        public String token(String repository) {
                            return "synthetic";
                        }

                        @Override
                        public String actor() {
                            return "poc[bot]";
                        }
                    });
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
