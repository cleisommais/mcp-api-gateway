package com.prreview.apigateway.controller;

import com.prreview.apigateway.dto.CreateReviewRequest;
import com.prreview.apigateway.service.ReviewRequestService;
import com.prreview.common.json.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Manual API controller: authentication, DTO validation, service invocation and HTTP response. */
public final class ReviewController implements HttpHandler {
    private final ReviewRequestService reviews;
    private final byte[] bearer;

    public ReviewController(ReviewRequestService reviews, String token) {
        if (token == null || token.length() < 32)
            throw new IllegalArgumentException("API token must be configured");
        this.reviews = reviews;
        bearer = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void handle(HttpExchange exchange) throws java.io.IOException {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (authorization == null
                || !MessageDigest.isEqual(bearer, authorization.getBytes(StandardCharsets.UTF_8))) {
            HttpResponses.json(exchange, 401, Map.of("error", "unauthorized"));
            return;
        }
        try {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/api/reviews")) {
                if (!exchange.getRequestMethod().equals("POST")) {
                    HttpResponses.json(exchange, 405, Map.of("error", "POST-required"));
                    return;
                }
                byte[] bytes = exchange.getRequestBody().readNBytes(16_385);
                if (bytes.length > 16_384) {
                    HttpResponses.json(exchange, 413, Map.of("error", "body-too-large"));
                    return;
                }
                Map<String, Object> body;
                try {
                    body = Json.object(new String(bytes, StandardCharsets.UTF_8));
                } catch (Exception invalid) {
                    throw new IllegalArgumentException("Invalid JSON body");
                }
                if (!body.keySet().equals(Set.of("platform", "repository", "number"))
                        || !(body.get("number") instanceof Integer number)) {
                    throw new IllegalArgumentException(
                            "Expected exactly platform, repository and integer number");
                }
                var result =
                        reviews.create(
                                new CreateReviewRequest(
                                        Json.text(body, "platform"),
                                        Json.text(body, "repository"),
                                        number));
                exchange.getResponseHeaders().set("Location", result.statusUrl());
                HttpResponses.json(exchange, result.duplicate() ? 200 : 202, result);
            } else if (path.matches("/api/reviews/[a-f0-9-]{36}")
                    && exchange.getRequestMethod().equals("GET")) {
                var result =
                        reviews.status(UUID.fromString(path.substring("/api/reviews/".length())));
                HttpResponses.json(
                        exchange,
                        result == null ? 404 : 200,
                        result == null ? Map.of("error", "job-not-found") : result);
            } else HttpResponses.json(exchange, 404, Map.of("error", "route-not-found"));
        } catch (SecurityException denied) {
            HttpResponses.json(exchange, 403, Map.of("error", denied.getMessage()));
        } catch (IllegalArgumentException invalid) {
            HttpResponses.json(exchange, 400, Map.of("error", invalid.getMessage()));
        } catch (Exception unavailable) {
            System.err.println(
                    "Manual review request failed: " + unavailable.getClass().getSimpleName());
            HttpResponses.json(exchange, 503, Map.of("error", "review-dependency-unavailable"));
        }
    }
}
