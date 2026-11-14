package dev.cleisommais.mcp.f6;

import com.sun.net.httpserver.HttpServer;
import dev.cleisommais.mcp.f2.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class OpenAiModelTest {
    @TempDir Path directory;
    private CampaignBudget budget() { return new CampaignBudget(directory.resolve("budget.json"), new BigDecimal("0.10")); }
    private OpenAiModel model(URI base, CampaignBudget budget) {
        return new OpenAiModel(base, "gpt-6-luna", "synthetic-key", "medium", 4096, Duration.ofSeconds(2), budget,
                new BigDecimal("0.10"), new BigDecimal("0.50"));
    }
    private Map<String, Object> response(String status, String contentType) {
        return Map.of("status", status, "model", "gpt-6-luna", "service_tier", "default",
                "usage", Map.of("input_tokens", 100, "input_tokens_details", Map.of("cached_tokens", 10, "cache_write_tokens", 20),
                        "output_tokens", 200, "output_tokens_details", Map.of("reasoning_tokens", 150)),
                "output", List.of(Map.of("type", "reasoning"), Map.of("type", "message",
                        "content", List.of(Map.of("type", contentType, "text", "{}")))));
    }
    @Test void sendsStrictSchemaWithoutToolsAndCountsCacheAndReasoningOnce() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/responses", exchange -> {
            assertEquals("Bearer synthetic-key", exchange.getRequestHeaders().getFirst("Authorization"));
            var request = Json.object(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(false, request.get("store")); assertFalse(request.containsKey("tools"));
            assertEquals(4096, request.get("max_output_tokens"));
            assertEquals(true, Json.map(Json.map(request.get("text")).get("format")).get("strict"));
            byte[] body = Json.write(response("completed", "output_text")).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); try (var output = exchange.getResponseBody()) { output.write(body); }
        }); server.start();
        try {
            var model = model(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), budget());
            var generation = model.generate("policy", "public-data", Map.of("type", "object"));
            assertEquals("{}", generation.content()); assertEquals(100, generation.promptTokens()); assertEquals(200, generation.outputTokens());
            var metric = Json.map(Json.list(model.metadata().get("attempts")).getFirst());
            // 70 uncached + 10 cache reads at 0.1x + 20 cache writes at 1.25x; output already includes 150 reasoning tokens.
            assertEquals(0, new BigDecimal("0.0001096").compareTo(new BigDecimal(metric.get("estimatedUsd").toString())));
            var journal = Json.object(Files.readString(directory.resolve("budget.json")));
            assertEquals("completed", Json.map(Json.list(journal.get("attempts")).getFirst()).get("outcome"));
            assertFalse(Files.readString(directory.resolve("budget.json")).contains("synthetic-key"));
        } finally { server.stop(0); }
    }
    @Test void incompleteAndRefusedResponsesPreserveUsageAndReservations() throws Exception {
        for (String status : List.of("incomplete", "completed")) {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/responses", exchange -> {
                byte[] body = Json.write(response(status, "refusal")).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length); try (var output = exchange.getResponseBody()) { output.write(body); }
            }); server.start();
            try {
                var model = model(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), budget());
                assertThrows(java.io.IOException.class, () -> model.generate("policy", "data", Map.of()));
                assertEquals(1, Json.list(model.metadata().get("attempts")).size());
            } finally { server.stop(0); }
        }
        assertEquals(2, Json.list(Json.object(Files.readString(directory.resolve("budget.json"))).get("attempts")).size());
    }
    @Test void httpFailureIsNeverRetriedAndDoesNotExposeTheBody() throws Exception {
        var count = new AtomicInteger(); var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/responses", exchange -> {
            count.incrementAndGet(); byte[] body = "synthetic-sensitive-response".getBytes();
            exchange.sendResponseHeaders(429, body.length); try (var output = exchange.getResponseBody()) { output.write(body); }
        }); server.start();
        try {
            var model = model(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), budget());
            var error = assertThrows(java.io.IOException.class, () -> model.generate("policy", "data", Map.of()));
            assertEquals(1, count.get()); assertFalse(error.getMessage().contains("sensitive"));
            assertEquals("http-429", Json.map(Json.list(Json.object(Files.readString(directory.resolve("budget.json"))).get("attempts")).getFirst()).get("outcome"));
        } finally { server.stop(0); }
    }
    @Test void rejectsUnapprovedEndpointsBeforeReadingAKey() {
        for (String endpoint : List.of("http://api.openai.com/v1/", "https://example.com/v1/", "https://api.openai.com/v1/?token=x", "https://api.openai.com:443/v1/")) {
            assertThrows(IllegalArgumentException.class, () -> OpenAiModel.configured(URI.create(endpoint), "gpt-6-luna", directory.resolve("missing-key"),
                    "medium", 4096, Duration.ofSeconds(2), budget(), BigDecimal.ONE, BigDecimal.ONE));
        }
    }
    @Test void malformedKeyIsRejectedWithoutEchoingItsContents() {
        var error = assertThrows(IllegalArgumentException.class, () -> new OpenAiModel(URI.create("http://127.0.0.1/"),
                "gpt-6-luna", "synthetic-sensitive\nkey", "medium", 4096, Duration.ofSeconds(2), budget(), BigDecimal.ONE, BigDecimal.ONE));
        assertFalse(error.getMessage().contains("synthetic-sensitive"));
    }
    @Test void reservationLimitSurvivesRestartAndUnknownFailures() throws Exception {
        Path path = directory.resolve("restart.json");
        var first = new CampaignBudget(path, new BigDecimal("0.01"));
        String id = first.reserve(new BigDecimal("0.006")); first.record(id, "unknown", Map.of());
        var restarted = new CampaignBudget(path, new BigDecimal("0.01"));
        assertThrows(java.io.IOException.class, () -> restarted.reserve(new BigDecimal("0.006")));
        assertEquals(1, Json.list(Json.object(Files.readString(path)).get("attempts")).size());
    }
    @Test void malformedOrOverrunCampaignCannotSilentlyReset() throws Exception {
        Path path = directory.resolve("blocked.json"); var budget = new CampaignBudget(path, new BigDecimal("0.10"));
        String id = budget.reserve(new BigDecimal("0.001")); budget.record(id, "reservation-exceeded", Map.of());
        assertThrows(java.io.IOException.class, () -> budget.reserve(new BigDecimal("0.001")));
        Files.writeString(path, "{broken");
        assertThrows(Exception.class, () -> budget.reserve(new BigDecimal("0.001")));
        assertThrows(IllegalArgumentException.class, () -> new CampaignBudget(path, BigDecimal.ZERO));
    }
}
