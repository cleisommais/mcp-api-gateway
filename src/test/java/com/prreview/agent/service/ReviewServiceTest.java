package com.prreview.agent.service;

import static org.junit.jupiter.api.Assertions.*;

import com.prreview.common.json.Json;
import com.prreview.domain.model.ChangeSnapshot;
import com.prreview.domain.model.FileChange;
import com.prreview.domain.port.ChangeSource;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class ReviewServiceTest {
    @Test
    void defaultsToRetainedOriginalPromptAndAllowsExplicitCalibration() throws Exception {
        for (boolean calibrated : List.of(false, true)) {
            var model =
                    new com.prreview.domain.port.ReviewModel() {
                        public String name() {
                            return "prompt-profile-fixture";
                        }

                        public com.prreview.domain.model.ModelGeneration generate(
                                String system, String context, Map<String, Object> schema)
                                throws Exception {
                            if (calibrated)
                                assertTrue(
                                        system.contains(
                                                "Severity must reflect demonstrated impact"));
                            else assertTrue(system.startsWith(ReviewEngine.SYSTEM));
                            return new com.prreview.domain.model.ModelGeneration(
                                    Json.write(
                                            Map.of(
                                                    "summary",
                                                    "Fixture output",
                                                    "findings",
                                                    List.of())),
                                    1,
                                    1,
                                    1);
                        }
                    };
            Map<String, String> env =
                    calibrated ? Map.of("REVIEW_PROMPT_PROFILE", "calibrated") : Map.of();
            try (var session = new ReviewService(env, "github", "owner/repo", model)) {
                var report = session.review(source());
                assertEquals(
                        calibrated ? "review-calibrated-v2" : "review-original-v3",
                        report.get("promptVersion"));
                assertEquals("complete", report.get("status"));
            }
        }
    }

    private ChangeSource source() {
        var snapshot =
                new ChangeSnapshot(
                        "github",
                        "owner/repo",
                        1,
                        "",
                        "",
                        "a".repeat(40),
                        "b".repeat(40),
                        "owner/repo",
                        "open",
                        1,
                        true,
                        List.of(
                                new FileChange(
                                        "A.java",
                                        "modified",
                                        "@@ -1 +1 @@\n-old();\n+broken();",
                                        true,
                                        1,
                                        1,
                                        "source")));
        return new ChangeSource() {
            @Override
            public ChangeSnapshot snapshot() {
                return snapshot;
            }

            @Override
            public String fileAtCommit(String sha, String path) {
                return "broken();";
            }

            @Override
            public boolean isCurrent(ChangeSnapshot ignored) {
                return true;
            }

            @Override
            public void close() {}
        };
    }

    @Test
    void bothRolesShareACallBudgetAndExhaustionIsPartial() throws Exception {
        exercise(false, false);
    }

    @Test
    void reviewDeadlineCancelsAnInFlightCallWithoutFallback() throws Exception {
        exercise(true, false);
    }

    @Test
    void incompleteGenerationsRetainObservedTokensInThePartialReport() throws Exception {
        exercise(false, true);
    }

    private void exercise(boolean slow, boolean truncated) throws Exception {
        var count = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    Object response;
                    switch (exchange.getRequestURI().getPath()) {
                        case "/api/version" -> response = Map.of("version", "synthetic");
                        case "/api/show" -> response = Map.of("details", Map.of());
                        case "/api/tags" -> response = Map.of("models", List.of());
                        case "/api/chat" -> {
                            count.incrementAndGet();
                            if (slow)
                                try {
                                    Thread.sleep(2200);
                                } catch (InterruptedException error) {
                                    Thread.currentThread().interrupt();
                                }
                            String result =
                                    Json.write(
                                            Map.of(
                                                    "summary",
                                                    "Bug",
                                                    "findings",
                                                    List.of(
                                                            Map.of(
                                                                    "file",
                                                                    "A.java",
                                                                    "line",
                                                                    1,
                                                                    "evidence",
                                                                    "broken();",
                                                                    "severity",
                                                                    "HIGH",
                                                                    "explanation",
                                                                    "Concrete failure"))));
                            response =
                                    Map.of(
                                            "done",
                                            true,
                                            "done_reason",
                                            truncated ? "length" : "stop",
                                            "message",
                                            Map.of("content", result),
                                            "prompt_eval_count",
                                            12,
                                            "eval_count",
                                            4096);
                        }
                        default -> throw new AssertionError("Unexpected endpoint");
                    }
                    byte[] bytes =
                            Json.write(response).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    try {
                        exchange.sendResponseHeaders(200, bytes.length);
                        try (var output = exchange.getResponseBody()) {
                            output.write(bytes);
                        }
                    } catch (java.io.IOException cancelled) {
                        exchange.close();
                    }
                });
        server.start();
        try {
            var env =
                    Map.of(
                            "OLLAMA_BASE_URL",
                            "http://127.0.0.1:" + server.getAddress().getPort() + "/",
                            "REVIEW_MODE",
                            slow ? "single" : "verified",
                            "REVIEW_MAX_MODEL_CALLS",
                            "1",
                            "REVIEW_TOTAL_SECONDS",
                            slow ? "1" : "30",
                            "REVIEW_TIMEOUT_SECONDS",
                            "5");
            try (var session = new ReviewService(env, "github", "owner/repo")) {
                var report = session.review(source());
                assertEquals("partial", report.get("status"));
                assertEquals(1, count.get());
                if (slow) assertTrue(report.get("errors").toString().contains("TimeoutException"));
                else
                    assertEquals(
                            "incomplete-or-disputed",
                            Json.map(report.get("verification")).get("status"));
                assertEquals(false, Json.map(report.get("inference")).get("automaticFallback"));
                if (truncated) {
                    assertEquals(12L, Json.map(report.get("usage")).get("promptTokens"));
                    assertEquals(4096L, Json.map(report.get("usage")).get("outputTokens"));
                    assertEquals(0L, Json.map(report.get("usage")).get("unknownUsageAttempts"));
                }
            }
        } finally {
            server.stop(0);
        }
    }
}
