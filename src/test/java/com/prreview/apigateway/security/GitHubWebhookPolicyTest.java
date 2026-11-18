package com.prreview.apigateway.security;

import static org.junit.jupiter.api.Assertions.*;

import com.prreview.apigateway.config.AllowedRepository;
import com.prreview.apigateway.config.ApiGatewayConfig;
import com.prreview.common.json.Json;
import com.prreview.domain.model.ReviewEvent;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class GitHubWebhookPolicyTest {
    static final String SECRET = "test-webhook-secret-" + "x".repeat(32);
    static final String REPO = "owner/private-repo";
    static final String BASE = "a".repeat(40), HEAD = "b".repeat(40);

    static GitHubWebhookPolicy policy() {
        return new GitHubWebhookPolicy(
                SECRET, 42, new ApiGatewayConfig(List.of(new AllowedRepository(17, REPO, "main"))));
    }

    static Map<String, Object> payload() {
        return new HashMap<>(
                Map.of(
                        "action",
                        "opened",
                        "installation",
                        Map.of("id", 42),
                        "repository",
                        Map.of("id", 17, "full_name", REPO),
                        "number",
                        1,
                        "pull_request",
                        new HashMap<>(
                                Map.of(
                                        "base",
                                        Map.of("sha", BASE, "ref", "main"),
                                        "head",
                                        Map.of("sha", HEAD, "repo", Map.of("full_name", REPO)),
                                        "state",
                                        "open",
                                        "draft",
                                        false,
                                        "author_association",
                                        "OWNER",
                                        "updated_at",
                                        "2026-11-13T20:00:00Z"))));
    }

    static String signature(byte[] body) throws Exception {
        var mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(
                new javax.crypto.spec.SecretKeySpec(
                        SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + java.util.HexFormat.of().formatHex(mac.doFinal(body));
    }

    public static ReviewEvent event() throws Exception {
        byte[] body = Json.write(payload()).getBytes(StandardCharsets.UTF_8);
        return policy().validate(
                        UUID.randomUUID().toString(), "pull_request", signature(body), body);
    }

    @Test
    void acceptsOnlyUntamperedSignedAuthorizedSnapshot() throws Exception {
        byte[] body = Json.write(payload()).getBytes(StandardCharsets.UTF_8);
        var accepted =
                policy().validate(
                                UUID.randomUUID().toString(),
                                "pull_request",
                                signature(body),
                                body);
        assertTrue(accepted.eligible());
        assertEquals(HEAD, accepted.headSha());
        assertThrows(
                SecurityException.class,
                () ->
                        policy().validate(
                                        UUID.randomUUID().toString(),
                                        "pull_request",
                                        signature(body),
                                        "{}".getBytes(StandardCharsets.UTF_8)));
        assertThrows(
                SecurityException.class,
                () -> policy().validate(UUID.randomUUID().toString(), "pull_request", null, body));
    }

    @Test
    void deniesOtherRepositoryAndInstallationEvenWithValidSignature() throws Exception {
        for (String field : List.of("repository", "installation")) {
            var data = payload();
            data.put(
                    field,
                    field.equals("repository")
                            ? Map.of("id", 99, "full_name", "other/repo")
                            : Map.of("id", 999));
            byte[] body = Json.write(data).getBytes(StandardCharsets.UTF_8);
            assertThrows(
                    SecurityException.class,
                    () ->
                            policy().validate(
                                            UUID.randomUUID().toString(),
                                            "pull_request",
                                            signature(body),
                                            body));
        }
    }

    @Test
    void skipsDraftWrongBranchUntrustedAuthorForkAndIrrelevantAction() throws Exception {
        for (String scenario : List.of("draft", "branch", "author", "fork", "action")) {
            var data = payload();
            var pr = Json.map(data.get("pull_request"));
            switch (scenario) {
                case "draft" -> pr.put("draft", true);
                case "branch" -> pr.put("base", Map.of("sha", BASE, "ref", "other"));
                case "author" -> pr.put("author_association", "CONTRIBUTOR");
                case "fork" ->
                        pr.put(
                                "head",
                                Map.of("sha", HEAD, "repo", Map.of("full_name", "outside/fork")));
                default -> data.put("action", "edited");
            }
            byte[] body = Json.write(data).getBytes(StandardCharsets.UTF_8);
            assertFalse(
                    policy().validate(
                                    UUID.randomUUID().toString(),
                                    "pull_request",
                                    signature(body),
                                    body)
                            .eligible(),
                    scenario);
        }
    }

    @Test
    void acknowledgesSignedPingWithoutCreatingReviewOrRequiringRepository() throws Exception {
        byte[] body = "{\"zen\":\"Keep it simple\"}".getBytes(StandardCharsets.UTF_8);
        var event = policy().validate(UUID.randomUUID().toString(), "ping", signature(body), body);
        assertFalse(event.eligible());
        assertEquals("ping", event.reason());
    }

    @Test
    void rejectsMutableRefsAndMalformedJson() throws Exception {
        var data = payload();
        Json.map(data.get("pull_request"))
                .put("head", Map.of("sha", "main", "repo", Map.of("full_name", REPO)));
        byte[] body = Json.write(data).getBytes(StandardCharsets.UTF_8);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        policy().validate(
                                        UUID.randomUUID().toString(),
                                        "pull_request",
                                        signature(body),
                                        body));
        byte[] invalid = "invalid JSON".getBytes(StandardCharsets.UTF_8);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        policy().validate(
                                        UUID.randomUUID().toString(),
                                        "pull_request",
                                        signature(invalid),
                                        invalid));
    }
}
