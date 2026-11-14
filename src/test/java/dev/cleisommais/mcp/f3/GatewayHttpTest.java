package dev.cleisommais.mcp.f3;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GatewayHttpTest {
    private static final String SECRET = "test-only-credential-" + "x".repeat(32);

    @Test void authenticatesHttpAndRoundTripsScopedCallsOverRealMcpTransport() throws Exception {
        var events = new ArrayList<Map<String, Object>>();
        try (var gateway = new GatewayServer(GatewayServiceTest.config(8), SECRET, 0,
                (s, r, n) -> new GatewayServiceTest.FakeBackend(), events::add)) {
            String address = "http://localhost:" + gateway.port();
            var transport = HttpClientStreamableHttpTransport.builder(address).endpoint("/mcp")
                    .requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer " + SECRET)).build();
            var client = McpClient.sync(transport).initializationTimeout(Duration.ofSeconds(5)).requestTimeout(Duration.ofSeconds(5)).build();
            try {
                assertEquals("2025-11-25", client.initialize().protocolVersion());
                assertEquals(4, client.listTools().tools().size());
                var snapshot = client.callTool(McpSchema.CallToolRequest.builder("github.get_change_snapshot").arguments(GatewayServiceTest.scope(false)).build());
                assertFalse(Boolean.TRUE.equals(snapshot.isError()));
                assertEquals(GatewayServiceTest.SHA, ((Map<?, ?>) snapshot.structuredContent()).get("headSha"));
                var invalid = GatewayServiceTest.scope(true); invalid.put("sha", "c".repeat(40));
                var denied = client.callTool(McpSchema.CallToolRequest.builder("github.get_change_state").arguments(invalid).build());
                assertTrue(Boolean.TRUE.equals(denied.isError()));
                client.callTool(McpSchema.CallToolRequest.builder("github.close_review").arguments(GatewayServiceTest.scope(true)).build());
            } finally { client.closeGracefully(); }
            assertTrue(events.stream().anyMatch(event -> "allowed".equals(event.get("decision"))));
            assertTrue(events.stream().anyMatch(event -> "denied".equals(event.get("decision"))));
        }
    }

    @Test void rejectsMissingBadCredentialAndBrowserOriginBeforeMcp() throws Exception {
        try (var gateway = new GatewayServer(GatewayServiceTest.config(8), SECRET, 0,
                (s, r, n) -> { fail("Unauthenticated request reached downstream"); return null; }, e -> {})) {
            var uri = URI.create("http://localhost:" + gateway.port() + "/mcp");
            var http = HttpClient.newHttpClient();
            assertEquals(401, http.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode());
            assertEquals(401, http.send(HttpRequest.newBuilder(uri).header("Authorization", "Bearer wrong").GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode());
            assertEquals(403, http.send(HttpRequest.newBuilder(uri).header("Authorization", "Bearer " + SECRET)
                    .header("Origin", "https://example.invalid").GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode());
        }
    }
}
