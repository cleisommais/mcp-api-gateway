package com.prreview.apigateway.controller;

import com.prreview.apigateway.config.ApiGatewayConfig;
import com.prreview.apigateway.service.WebhookAdmissionService;
import com.prreview.common.json.Json;
import com.prreview.domain.model.WebhookRequest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Provider-authenticated callbacks. Only an incoming event initiates discovery/admission. */
public final class PlatformWebhookController implements HttpHandler {
    private final String platform;
    private final String secret;
    private final ApiGatewayConfig policy;
    private final WebhookAdmissionService admissions;

    public PlatformWebhookController(
            String platform,
            String secret,
            ApiGatewayConfig policy,
            WebhookAdmissionService admissions) {
        if (!Set.of("gitlab", "bitbucket").contains(platform) || secret.length() < 32)
            throw new IllegalArgumentException("Expected platform and generated webhook secret");
        this.platform = platform;
        this.secret = secret;
        this.policy = policy;
        this.admissions = admissions;
    }

    private void authenticate(HttpExchange exchange, byte[] body) throws Exception {
        if (platform.equals("gitlab")) {
            String received = exchange.getRequestHeaders().getFirst("X-Gitlab-Token");
            if (received == null
                    || !MessageDigest.isEqual(
                            secret.getBytes(StandardCharsets.UTF_8),
                            received.getBytes(StandardCharsets.UTF_8)))
                throw new SecurityException();
        } else {
            String signature = exchange.getRequestHeaders().getFirst("X-Hub-Signature");
            if (signature == null || !signature.matches("sha256=[a-f0-9]{64}"))
                throw new SecurityException();
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            if (!MessageDigest.isEqual(
                    mac.doFinal(body), HexFormat.of().parseHex(signature.substring(7))))
                throw new SecurityException();
        }
    }

    private Map<String, Object> admit(
            String repository,
            int number,
            String head,
            String delivery,
            String hash,
            String event,
            String action)
            throws Exception {
        UUID id =
                UUID.nameUUIDFromBytes(
                        (platform + ":" + delivery + ":" + number)
                                .getBytes(StandardCharsets.UTF_8));
        boolean added =
                admissions.accept(
                        new WebhookRequest(
                                id,
                                platform,
                                delivery,
                                repository,
                                number,
                                "",
                                head,
                                event,
                                action,
                                hash));
        return Map.of("receiptId", id.toString(), "duplicate", !added);
    }

    private void bitbucketPr(
            Map<String, Object> pr,
            String repository,
            String delivery,
            String hash,
            String event,
            List<Map<String, Object>> receipts)
            throws Exception {
        var rule = policy.target(platform, repository);
        var source = Json.map(pr.get("source"));
        var destination = Json.map(pr.get("destination"));
        if (!"OPEN".equals(pr.get("state"))
                || Boolean.TRUE.equals(pr.get("draft"))
                || !rule.baseBranch().equals(Json.text(Json.map(destination.get("branch")), "name"))
                || !repository.equals(Json.text(Json.map(source.get("repository")), "full_name")))
            return;
        receipts.add(
                admit(
                        repository,
                        ((Number) pr.get("id")).intValue(),
                        Json.text(Json.map(source.get("commit")), "hash"),
                        delivery,
                        hash,
                        event,
                        event));
    }

    @Override
    public void handle(HttpExchange exchange) throws java.io.IOException {
        try {
            if (!exchange.getRequestURI().getPath().equals("/webhooks/" + platform)
                    || !exchange.getRequestMethod().equals("POST")) {
                HttpResponses.json(exchange, 405, Map.of("error", "POST-required"));
                return;
            }
            byte[] body = exchange.getRequestBody().readNBytes(1_000_001);
            if (body.length > 1_000_000) {
                HttpResponses.json(exchange, 413, Map.of("error", "body-too-large"));
                return;
            }
            authenticate(exchange, body);
            var payload = Json.object(new String(body, StandardCharsets.UTF_8));
            String hash =
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            String delivery =
                    exchange.getRequestHeaders()
                            .getFirst(platform.equals("gitlab") ? "webhook-id" : "X-Request-UUID");
            if (delivery == null && platform.equals("gitlab"))
                delivery = exchange.getRequestHeaders().getFirst("X-Gitlab-Webhook-UUID");
            if (delivery == null || !delivery.matches("[A-Za-z0-9{}_-]{1,100}"))
                throw new IllegalArgumentException("Missing delivery identity");
            var receipts = new ArrayList<Map<String, Object>>();
            if (platform.equals("gitlab")) {
                if ("Merge Request Hook"
                                .equals(exchange.getRequestHeaders().getFirst("X-Gitlab-Event"))
                        && "merge_request".equals(payload.get("object_kind"))) {
                    var project = Json.map(payload.get("project"));
                    String repository = Json.text(project, "path_with_namespace");
                    var rule = policy.target(platform, repository);
                    if (((Number) project.get("id")).longValue() != rule.id())
                        throw new SecurityException();
                    var attributes = Json.map(payload.get("object_attributes"));
                    String action = Json.text(attributes, "action");
                    if (Set.of("open", "reopen", "update").contains(action)
                            && "opened".equals(attributes.get("state"))
                            && !Boolean.TRUE.equals(attributes.get("draft"))
                            && !Boolean.TRUE.equals(attributes.get("work_in_progress"))
                            && attributes
                                    .get("source_project_id")
                                    .equals(attributes.get("target_project_id"))
                            && rule.baseBranch().equals(attributes.get("target_branch"))) {
                        receipts.add(
                                admit(
                                        repository,
                                        ((Number) attributes.get("iid")).intValue(),
                                        Json.text(Json.map(attributes.get("last_commit")), "id"),
                                        delivery,
                                        hash,
                                        "merge_request",
                                        action));
                    }
                }
            } else {
                String repository = Json.text(Json.map(payload.get("repository")), "full_name");
                policy.target(platform, repository);
                String event = exchange.getRequestHeaders().getFirst("X-Event-Key");
                if (Set.of("pullrequest:created", "pullrequest:updated").contains(event)) {
                    bitbucketPr(
                            Json.map(payload.get("pullrequest")),
                            repository,
                            delivery,
                            hash,
                            event,
                            receipts);
                } else if ("repo:push".equals(event)) {
                    for (var change : Json.list(Json.map(payload.get("push")).get("changes"))) {
                        var next = Json.map(Json.map(change).get("new"));
                        if (!"branch".equals(next.get("type"))) continue;
                        String branch = Json.text(next, "name");
                        if (!branch.matches("[A-Za-z0-9/_\\-.]{1,200}")) continue;
                        String head = Json.text(Json.map(next.get("target")), "hash");
                        if (!head.matches("[a-f0-9]{40}"))
                            throw new IllegalArgumentException("Invalid pushed head");
                        UUID id =
                                UUID.nameUUIDFromBytes(
                                        (platform + ":" + delivery + ":" + branch)
                                                .getBytes(StandardCharsets.UTF_8));
                        boolean added =
                                admissions.accept(
                                        new WebhookRequest(
                                                id,
                                                platform,
                                                delivery,
                                                repository,
                                                0,
                                                branch,
                                                head,
                                                event,
                                                event,
                                                hash));
                        receipts.add(Map.of("receiptId", id.toString(), "duplicate", !added));
                    }
                }
            }
            HttpResponses.json(
                    exchange,
                    receipts.isEmpty() ? 200 : 202,
                    Map.of(
                            "outcome",
                            receipts.isEmpty() ? "ignored" : "accepted",
                            "reviews",
                            receipts));
        } catch (SecurityException denied) {
            HttpResponses.json(exchange, 403, Map.of("error", "webhook-not-authorized"));
        } catch (IllegalArgumentException invalid) {
            HttpResponses.json(exchange, 400, Map.of("error", "invalid-event"));
        } catch (Exception unavailable) {
            System.err.println(
                    "Webhook " + platform + " failed; " + unavailable.getClass().getSimpleName());
            HttpResponses.json(
                    exchange, 503, Map.of("error", "dependency-or-configuration-unavailable"));
        }
    }
}
