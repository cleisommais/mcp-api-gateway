package com.prreview.tools.fixture;

import static org.junit.jupiter.api.Assertions.*;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.Map;
import java.util.stream.Stream;

/** Exercises real JSON-RPC between two JVMs without mocking the MCP client or server. */
public class McpRoundTripTest {
    private McpSyncClient client;

    @AfterEach
    void closeConnection() {
        if (client != null) {
            client.closeGracefully();
        }
    }

    @Test
    void discoversSchemaAndReadsKnownDiffOverStdio() {
        startServer();
        var tools = client.listTools().tools();
        assertEquals(1, tools.size());
        assertEquals(ReviewFixtureTool.NAME, tools.getFirst().name());
        assertEquals("object", tools.getFirst().inputSchema().get("type"));

        var result = call(Map.of("scenario", "off-by-one"));
        assertFalse(Boolean.TRUE.equals(result.isError()));
        assertInstanceOf(Map.class, result.structuredContent());
        var fixture = (Map<?, ?>) result.structuredContent();
        assertEquals("src/TotalCalculator.java", fixture.get("file"));
        assertEquals("1111111111111111111111111111111111111111", fixture.get("commit"));
        assertTrue(
                fixture.get("diff")
                        .toString()
                        .contains("+        for (int i = 0; i <= values.length; i++)"));
        assertEquals(
                fixture.get("diff"), ((McpSchema.TextContent) result.content().getFirst()).text());
    }

    @ParameterizedTest
    @MethodSource("invalidArguments")
    void rejectsInvalidArgumentsAndKeepsConnectionUsable(Map<String, Object> arguments) {
        startServer();
        var result = call(arguments);
        assertTrue(
                Boolean.TRUE.equals(result.isError()), "An invalid call must return a tool error");
        assertFalse(result.content().isEmpty());
        assertFalse(Boolean.TRUE.equals(call(Map.of("scenario", "off-by-one")).isError()));
    }

    static Stream<Map<String, Object>> invalidArguments() {
        return Stream.of(
                Map.of(),
                Map.of("scenario", 42),
                Map.of("scenario", ""),
                Map.of("scenario", "unknown"),
                Map.of("scenario", "off-by-one", "path", "../../secret"));
    }

    @Test
    void reportsServerStartupFailureWithinTimeout() {
        client =
                McpFixtureCli.connectTo(
                        "com.prreview.tools.fixture.MissingServer", Duration.ofSeconds(2));
        assertThrows(RuntimeException.class, client::initialize);
    }

    private void startServer() {
        client = McpFixtureCli.connectTo(FixtureMcpServer.class.getName(), Duration.ofSeconds(5));
        var initialized = client.initialize();
        assertEquals("review-fixture-server", initialized.serverInfo().name());
        assertEquals("2025-11-25", initialized.protocolVersion());
    }

    private McpSchema.CallToolResult call(Map<String, Object> arguments) {
        return client.callTool(
                McpSchema.CallToolRequest.builder(ReviewFixtureTool.NAME)
                        .arguments(arguments)
                        .build());
    }
}
