package dev.cleisommais.mcp.f4;

import com.sun.net.httpserver.HttpServer;
import dev.cleisommais.mcp.f2.Json;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.net.InetSocketAddress;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ReviewPublisherTest {
    static JobStore.Job job() { return new JobStore.Job(UUID.randomUUID(), 17, WebhookPolicyTest.REPO, 1, 42,
            WebhookPolicyTest.BASE, WebhookPolicyTest.HEAD, "main", "running", 1, null, "none", null); }
    static Map<String, Object> report() {
        return Map.of("status", "complete", "enumeratedFiles", 1, "model", "fixture", "coverage", List.of(Map.of("status", "analyzed")),
                "findings", List.of(Map.of("file", "Example.java", "line", 1, "severity", "MEDIUM", "explanation", "Incorrect result @someone <img> ![x](https://example.invalid)")));
    }
    @Test void createsOneBotSummaryAndReusesItOnRetry() throws Exception {
        try (var fixture = new Api()) {
            var publisher = new ReviewPublisher(fixture.access()); var job = job();
            long id = publisher.publish(job, report(), false);
            assertEquals(id, publisher.publish(job, report(), true)); assertEquals(1, fixture.posts); assertEquals(1, fixture.patches);
            String body = (String) fixture.comments.getFirst().get("body");
            assertTrue(body.contains(job.headSha())); assertFalse(body.contains("@someone")); assertFalse(body.contains("<img>"));
            assertTrue(body.contains("human review"));
        }
    }
    @Test void reconcilesResponseLostAfterCommentCreationWithoutAnotherPost() throws Exception {
        try (var fixture = new Api()) {
            fixture.failAfterCreate = true;
            assertEquals(77, new ReviewPublisher(fixture.access()).publish(job(), report(), false));
            assertEquals(1, fixture.posts); assertEquals(1, fixture.comments.size());
        }
    }
    @Test void uncertainMissingCommentDoesNotBlindlyRepost() throws Exception {
        try (var fixture = new Api()) {
            assertThrows(ReviewPublisher.Uncertain.class, () -> new ReviewPublisher(fixture.access()).publish(job(), report(), true));
            assertEquals(0, fixture.posts);
        }
    }
    @Test void ignoresAnotherActorsCopiedMarker() throws Exception {
        try (var fixture = new Api()) {
            fixture.comments.add(new HashMap<>(Map.of("id", 22, "user", Map.of("login", "other-user"), "body", "<!-- mcp-review-poc:17:1 -->\nSpoofed")));
            new ReviewPublisher(fixture.access()).publish(job(), report(), false);
            assertEquals(1, fixture.posts); assertEquals(0, fixture.patches);
        }
    }
    @Test void preventsOldSnapshotAndMarksRaceDuringPublicationOutdated() throws Exception {
        try (var fixture = new Api()) {
            fixture.changed = true;
            assertThrows(ReviewPublisher.Outdated.class, () -> new ReviewPublisher(fixture.access()).publish(job(), report(), false));
            assertEquals(0, fixture.posts);
            fixture.changed = false; fixture.changeAfterWrite = true;
            assertThrows(ReviewPublisher.Outdated.class, () -> new ReviewPublisher(fixture.access()).publish(job(), report(), false));
            assertEquals(1, fixture.posts); assertEquals(1, fixture.patches);
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
            server.createContext("/", exchange -> {
                Object response; int status = 200;
                String method = exchange.getRequestMethod();
                if (exchange.getRequestURI().getPath().contains("/pulls/")) {
                    response = Map.of("state", "open", "draft", false, "head", Map.of("sha", changed ? "c".repeat(40) : WebhookPolicyTest.HEAD),
                            "base", Map.of("sha", WebhookPolicyTest.BASE, "ref", "main"));
                } else if (method.equals("GET")) response = comments;
                else {
                    var request = Json.object(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                    if (method.equals("POST")) {
                        posts++; comments.add(new HashMap<>(Map.of("id", 77L, "user", Map.of("login", "poc[bot]"), "body", request.get("body"))));
                        status = failAfterCreate ? 500 : 201; if (changeAfterWrite) changed = true;
                    } else { patches++; comments.getLast().put("body", request.get("body")); }
                    response = Map.of("id", 77);
                }
                byte[] bytes = Json.write(response).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length); try (var output = exchange.getResponseBody()) { output.write(bytes); }
            }); server.start();
        }
        GitHubAccess access() {
            return new GitHubAccess(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), new GitHubAccess.Credential() {
                @Override public String token(String repository) { return "synthetic"; }
                @Override public String actor() { return "poc[bot]"; }
            });
        }
        @Override public void close() { server.stop(0); }
    }
}
