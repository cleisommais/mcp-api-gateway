package com.prreview.apigateway.security;

import com.prreview.apigateway.config.ApiGatewayConfig;
import com.prreview.common.json.Json;
import com.prreview.domain.model.ReviewEvent;

import io.modelcontextprotocol.json.TypeRef;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Authenticate raw bytes before parsing or enqueueing any review work. */
public final class GitHubWebhookPolicy {

    private final byte[] secret;
    private final long installationId;
    private final ApiGatewayConfig config;

    public GitHubWebhookPolicy(String secret, long installationId, ApiGatewayConfig config) {
        if (secret == null || secret.length() < 32)
            throw new IllegalArgumentException("A generated webhook secret is required");
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.installationId = installationId;
        this.config = config;
    }

    public static ApiGatewayConfig read(Path path) throws Exception {
        return Json.read(Files.readString(path), new TypeRef<>() {});
    }

    public ApiGatewayConfig config() {
        return config;
    }

    public ReviewEvent validate(String delivery, String event, String signature, byte[] body)
            throws Exception {
        if (signature == null || !signature.matches("sha256=[a-f0-9]{64}"))
            throw new SecurityException("Missing or invalid webhook signature");
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        if (!MessageDigest.isEqual(
                mac.doFinal(body), HexFormat.of().parseHex(signature.substring(7))))
            throw new SecurityException("Webhook signature mismatch");
        if (delivery == null || !UUID.fromString(delivery).toString().equals(delivery))
            throw new IllegalArgumentException("Invalid delivery ID");
        if (event == null || !event.matches("[a-z_]{1,64}"))
            throw new IllegalArgumentException("Invalid event type");
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        Map<String, Object> payload;
        try {
            payload = Json.object(new String(body, StandardCharsets.UTF_8));
        } catch (java.io.IOException error) {
            throw new IllegalArgumentException("Invalid JSON payload");
        }
        if (event.equals("ping"))
            return new ReviewEvent(
                    delivery,
                    hash,
                    event,
                    "ping",
                    0,
                    "",
                    0,
                    installationId,
                    "",
                    "",
                    "",
                    Instant.EPOCH,
                    false,
                    "ping");
        if (installationId < 1)
            throw new IllegalStateException("GitHub installation is not configured");
        // Repository webhooks are HMAC-authenticated but have no installation object.
        // The configured installation remains the only identity used by the worker.
        long receivedInstallation =
                payload.containsKey("installation")
                        ? integer(Json.map(payload.get("installation")).get("id"))
                        : installationId;
        if (receivedInstallation != installationId)
            throw new SecurityException("Unexpected GitHub installation");
        var repository = Json.map(payload.get("repository"));
        long id = integer(repository.get("id"));
        String name = Json.text(repository, "full_name");
        var rule =
                config.repositories().stream()
                        .filter(
                                r ->
                                        r.platform().equals("github")
                                                && r.id() == id
                                                && r.name().equals(name))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new SecurityException(
                                                "Repository is not authorized for webhooks"));
        if (!"pull_request".equals(event))
            return new ReviewEvent(
                    delivery,
                    hash,
                    event,
                    "ignored",
                    id,
                    name,
                    0,
                    installationId,
                    "",
                    "",
                    "",
                    Instant.EPOCH,
                    false,
                    "irrelevant-event");
        String action = Json.text(payload, "action");
        var pr = Json.map(payload.get("pull_request"));
        int number = Math.toIntExact(integer(payload.get("number")));
        var base = Json.map(pr.get("base"));
        var head = Json.map(pr.get("head"));
        String baseSha = Json.text(base, "sha"),
                headSha = Json.text(head, "sha"),
                branch = Json.text(base, "ref");
        if (number < 1 || !baseSha.matches("[a-f0-9]{40}") || !headSha.matches("[a-f0-9]{40}"))
            throw new IllegalArgumentException("Invalid PR snapshot");
        String reason =
                !Set.of("opened", "synchronize", "reopened", "ready_for_review", "edited")
                                .contains(action)
                        ? "irrelevant-action"
                        : !"open".equals(pr.get("state"))
                                ? "closed"
                                : Boolean.TRUE.equals(pr.get("draft"))
                                        ? "draft"
                                        : !config.trustedAssociations()
                                                        .contains(pr.get("author_association"))
                                                ? "untrusted-contributor"
                                                : !config.allowForks()
                                                                && !name.equals(
                                                                        Json.map(head.get("repo"))
                                                                                .get("full_name"))
                                                        ? "fork-policy"
                                                        : "accepted";
        return new ReviewEvent(
                delivery,
                hash,
                event,
                action,
                id,
                name,
                number,
                installationId,
                baseSha,
                headSha,
                branch,
                Instant.parse(Json.text(pr, "updated_at")),
                reason.equals("accepted"),
                reason);
    }

    private static long integer(Object value) {
        if (!(value instanceof Integer || value instanceof Long))
            throw new IllegalArgumentException("Expected integer identifier");
        return ((Number) value).longValue();
    }
}
