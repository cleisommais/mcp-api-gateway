package com.prreview.apigateway.security;

import static org.junit.jupiter.api.Assertions.*;

import com.prreview.apigateway.ApiGatewayApplication;
import com.prreview.common.json.Json;
import com.prreview.domain.model.EnqueueResult;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public class ApiGatewayApplicationTest {
    @Test
    void persistsBefore202AndNeverQueuesInvalidSignature() throws Exception {
        var writes = new AtomicInteger();
        try (var edge =
                new ApiGatewayApplication(
                        0,
                        GitHubWebhookPolicyTest.policy(),
                        event -> {
                            writes.incrementAndGet();
                            return new EnqueueResult(UUID.randomUUID(), false, "accepted");
                        })) {
            byte[] body =
                    Json.write(GitHubWebhookPolicyTest.payload())
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var http = HttpClient.newHttpClient();
            var uri = URI.create("http://localhost:" + edge.port() + "/webhooks/github");
            var rejected =
                    http.send(
                            request(uri, body, "sha256=" + "0".repeat(64)),
                            HttpResponse.BodyHandlers.ofString());
            assertEquals(403, rejected.statusCode());
            assertEquals(0, writes.get());
            var accepted =
                    http.send(
                            request(uri, body, GitHubWebhookPolicyTest.signature(body)),
                            HttpResponse.BodyHandlers.ofString());
            assertEquals(202, accepted.statusCode());
            assertEquals(1, writes.get());
            assertTrue(accepted.body().contains("jobId"));
        }
    }

    @Test
    void databaseFailureDoesNotAcknowledgeAndLargeBodyIsRejected() throws Exception {
        try (var edge =
                new ApiGatewayApplication(
                        0,
                        GitHubWebhookPolicyTest.policy(),
                        event -> {
                            throw new java.sql.SQLException("fixture unavailable");
                        })) {
            var http = HttpClient.newHttpClient();
            var uri = URI.create("http://localhost:" + edge.port() + "/webhooks/github");
            byte[] body =
                    Json.write(GitHubWebhookPolicyTest.payload())
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(
                    503,
                    http.send(
                                    request(uri, body, GitHubWebhookPolicyTest.signature(body)),
                                    HttpResponse.BodyHandlers.discarding())
                            .statusCode());
            assertEquals(
                    413,
                    http.send(
                                    request(uri, new byte[1_000_001], "invalid"),
                                    HttpResponse.BodyHandlers.discarding())
                            .statusCode());
            assertEquals(
                    405,
                    http.send(
                                    HttpRequest.newBuilder(uri).GET().build(),
                                    HttpResponse.BodyHandlers.discarding())
                            .statusCode());
        }
    }

    private static HttpRequest request(URI uri, byte[] bytes, String signature) {
        return HttpRequest.newBuilder(uri)
                .header("X-GitHub-Delivery", UUID.randomUUID().toString())
                .header("X-GitHub-Event", "pull_request")
                .header("X-Hub-Signature-256", signature)
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build();
    }
}
