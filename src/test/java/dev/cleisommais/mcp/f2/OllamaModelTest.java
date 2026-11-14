package dev.cleisommais.mcp.f2;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class OllamaModelTest {
    private HttpServer server;
    @AfterEach void close() { if (server != null) server.stop(0); }

    @Test void sendsSchemaAndExplicitContextToOnlyConfiguredEndpoint() throws Exception {
        var captured = new AtomicReference<Map<String, Object>>();
        var model = start(captured, 200, "stop");
        var result = model.generate("policy", "data", ReviewEngine.SCHEMA);
        assertEquals("{}", result.content());
        var request = captured.get();
        assertEquals("fake", request.get("model"));
        assertEquals(false, request.get("stream"));
        assertEquals(false, request.get("think"));
        assertEquals(8192, Json.map(request.get("options")).get("num_ctx"));
        assertEquals(ReviewEngine.SCHEMA, request.get("format"));
    }

    @Test void rejectsTruncatedGeneration() throws Exception {
        var model = start(new AtomicReference<>(), 200, "length");
        var error = assertThrows(OllamaModel.IncompleteGeneration.class, () -> model.generate("policy", "data", ReviewEngine.SCHEMA));
        assertEquals(12, error.promptTokens); assertEquals(4096, error.outputTokens);
    }

    @Test void surfacesUnavailableModelWithoutFallback() throws Exception {
        var model = start(new AtomicReference<>(), 404, "stop");
        assertThrows(java.io.IOException.class, () -> model.generate("policy", "data", ReviewEngine.SCHEMA));
    }

    @Test void enforcesHttpTimeout() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            try { Thread.sleep(400); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        var model = new OllamaModel(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), "fake", 8192, Duration.ofMillis(100));
        assertThrows(java.net.http.HttpTimeoutException.class, () -> model.generate("policy", "data", ReviewEngine.SCHEMA));
    }

    @Test void sendsExplicitReasoningAndOutputBudget() throws Exception {
        var captured = new AtomicReference<Map<String, Object>>();
        start(captured, 200, "stop");
        var model = new OllamaModel(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), "fake", 8192,
                Duration.ofSeconds(2), true, 4096);
        model.generate("policy", "data", ReviewEngine.SCHEMA);
        assertEquals(true, captured.get().get("think"));
        assertEquals(4096, Json.map(captured.get().get("options")).get("num_predict"));
        assertThrows(IllegalArgumentException.class, () -> new OllamaModel(URI.create("http://localhost/"), "fake", 4096,
                Duration.ofSeconds(2), true, 4096));
    }

    @Test void supportsNamedReasoningLevelsWithoutSilentlyParsingThemAsFalse() throws Exception {
        var captured = new AtomicReference<Map<String, Object>>();
        start(captured, 200, "stop");
        var model = new OllamaModel(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), "fake", 8192,
                Duration.ofSeconds(2), OllamaModel.parseThinking("medium"), 4096);
        model.generate("policy", "data", ReviewEngine.SCHEMA);
        assertEquals("medium", captured.get().get("think"));
        assertThrows(IllegalArgumentException.class, () -> OllamaModel.parseThinking("invalid"));
    }

    private OllamaModel start(AtomicReference<Map<String, Object>> captured, int status, String reason) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            captured.set(Json.object(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] response = Json.write(Map.of("done", true, "done_reason", reason, "message", Map.of("content", "{}"),
                    "prompt_eval_count", 12, "eval_count", 4096))
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        return new OllamaModel(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), "fake", 8192, Duration.ofSeconds(2));
    }
}
