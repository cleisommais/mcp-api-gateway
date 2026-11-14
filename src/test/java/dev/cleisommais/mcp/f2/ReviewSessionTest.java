package dev.cleisommais.mcp.f2;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ReviewSessionTest {
    private ReviewData.Source source() {
        var snapshot = new ReviewData.Snapshot("github", "owner/repo", 1, "", "", "a".repeat(40), "b".repeat(40), "owner/repo", "open", 1, true,
                List.of(new ReviewData.FileChange("A.java", "modified", "@@ -1 +1 @@\n-old();\n+broken();", true, 1, 1, "source")));
        return new ReviewData.Source() {
            @Override public ReviewData.Snapshot snapshot() { return snapshot; }
            @Override public String fileAtCommit(String sha, String path) { return "broken();"; }
            @Override public boolean isCurrent(ReviewData.Snapshot ignored) { return true; }
            @Override public void close() {}
        };
    }
    @Test void bothRolesShareACallBudgetAndExhaustionIsPartial() throws Exception {
        exercise(false, false);
    }
    @Test void reviewDeadlineCancelsAnInFlightCallWithoutFallback() throws Exception {
        exercise(true, false);
    }
    @Test void incompleteGenerationsRetainObservedTokensInThePartialReport() throws Exception { exercise(false, true); }
    private void exercise(boolean slow, boolean truncated) throws Exception {
        var count = new AtomicInteger(); var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            Object response;
            switch (exchange.getRequestURI().getPath()) {
                case "/api/version" -> response = Map.of("version", "synthetic");
                case "/api/show" -> response = Map.of("details", Map.of());
                case "/api/tags" -> response = Map.of("models", List.of());
                case "/api/chat" -> {
                    count.incrementAndGet();
                    if (slow) try { Thread.sleep(2200); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                    String result = Json.write(Map.of("summary", "Bug", "findings", List.of(Map.of("file", "A.java", "line", 1,
                            "evidence", "broken();", "severity", "HIGH", "explanation", "Concrete failure"))));
                    response = Map.of("done", true, "done_reason", truncated ? "length" : "stop", "message", Map.of("content", result),
                            "prompt_eval_count", 12, "eval_count", 4096);
                }
                default -> throw new AssertionError("Unexpected endpoint");
            }
            byte[] bytes = Json.write(response).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try {
                exchange.sendResponseHeaders(200, bytes.length); try (var output = exchange.getResponseBody()) { output.write(bytes); }
            } catch (java.io.IOException cancelled) { exchange.close(); }
        }); server.start();
        try {
            var env = Map.of("OLLAMA_BASE_URL", "http://127.0.0.1:" + server.getAddress().getPort() + "/",
                    "REVIEW_MODE", slow ? "single" : "verified", "REVIEW_MAX_MODEL_CALLS", "1",
                    "REVIEW_TOTAL_SECONDS", slow ? "1" : "30", "REVIEW_TIMEOUT_SECONDS", "5");
            try (var session = new ReviewSession(env, "github", "owner/repo")) {
                var report = session.review(source()); assertEquals("partial", report.get("status"));
                assertEquals(1, count.get());
                if (slow) assertTrue(report.get("errors").toString().contains("TimeoutException"));
                else assertEquals("incomplete-or-disputed", Json.map(report.get("verification")).get("status"));
                assertEquals(false, Json.map(report.get("inference")).get("automaticFallback"));
                if (truncated) {
                    assertEquals(12L, Json.map(report.get("usage")).get("promptTokens"));
                    assertEquals(4096L, Json.map(report.get("usage")).get("outputTokens"));
                    assertEquals(0L, Json.map(report.get("usage")).get("unknownUsageAttempts"));
                }
            }
        } finally { server.stop(0); }
    }
}
