package com.prreview.apigateway.controller;

import com.prreview.apigateway.security.GitHubWebhookPolicy;
import com.prreview.domain.model.EnqueueResult;
import com.prreview.domain.model.ReviewEvent;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.util.Map;

/** GitHub webhook boundary: raw HMAC validation precedes admission and database persistence. */
public final class GitHubWebhookController implements HttpHandler {
    @FunctionalInterface
    public interface Queue {
        EnqueueResult enqueue(ReviewEvent event) throws Exception;
    }

    private final GitHubWebhookPolicy policy;
    private final Queue queue;

    public GitHubWebhookController(GitHubWebhookPolicy policy, Queue queue) {
        this.policy = policy;
        this.queue = queue;
    }

    @Override
    public void handle(HttpExchange exchange) throws java.io.IOException {
        int status;
        Map<String, Object> response;
        try {
            if (!exchange.getRequestURI().getPath().equals("/webhooks/github")
                    || !exchange.getRequestMethod().equals("POST")) {
                HttpResponses.json(exchange, 405, Map.of("error", "POST-required"));
                return;
            }
            byte[] bytes = exchange.getRequestBody().readNBytes(1_000_001);
            if (bytes.length > 1_000_000) {
                HttpResponses.json(exchange, 413, Map.of("error", "body-too-large"));
                return;
            }
            var headers = exchange.getRequestHeaders();
            var event =
                    policy.validate(
                            headers.getFirst("X-GitHub-Delivery"),
                            headers.getFirst("X-GitHub-Event"),
                            headers.getFirst("X-Hub-Signature-256"),
                            bytes);
            var receipt = queue.enqueue(event);
            status = receipt.jobId() != null && !receipt.duplicate() ? 202 : 200;
            response =
                    Map.of(
                            "outcome",
                            receipt.outcome(),
                            "duplicate",
                            receipt.duplicate(),
                            "jobId",
                            receipt.jobId() == null ? "" : receipt.jobId().toString());
        } catch (SecurityException denied) {
            status = 403;
            response = Map.of("error", "webhook-not-authorized");
        } catch (IllegalArgumentException invalid) {
            status = 400;
            response = Map.of("error", "invalid-event");
        } catch (Exception unavailable) {
            status = 503;
            response = Map.of("error", "dependency-or-configuration-unavailable");
        }
        HttpResponses.json(exchange, status, response);
    }
}
