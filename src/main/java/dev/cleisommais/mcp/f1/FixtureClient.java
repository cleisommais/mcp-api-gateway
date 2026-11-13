package dev.cleisommais.mcp.f1;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/** Minimal host: starts the server, discovers its tool, and calls it without a model. */
public final class FixtureClient {
    private FixtureClient() {
    }

    public static void main(String[] args) {
        if (args.length > 1 || (args.length == 1 && !"--server-unavailable".equals(args[0]))) {
            System.err.println("Usage: FixtureClient [--server-unavailable]");
            System.exit(2);
        }
        boolean unavailable = args.length == 1;
        String serverClass = unavailable ? "dev.cleisommais.mcp.f1.MissingServer" : FixtureServer.class.getName();
        var client = connectTo(serverClass, Duration.ofSeconds(5));
        int exitCode = 0;
        try {
            var initialized = client.initialize();
            System.out.println("initialize -> server: " + initialized.serverInfo().name());
            System.out.println("Negotiated protocol: " + initialized.protocolVersion());

            var tools = client.listTools();
            System.out.println("tools/list -> " + tools.tools().size() + " tool(s)");
            for (var tool : tools.tools()) {
                System.out.println("Tool: " + tool.name());
                System.out.println("Description: " + tool.description());
                System.out.println("Schema: " + McpJsonDefaults.getMapper().writeValueAsString(tool.inputSchema()));
            }

            callAndPrint(client, "Valid scenario", Map.of("scenario", ReviewFixtureTool.SCENARIO), false);
            callAndPrint(client, "Unknown scenario", Map.of("scenario", "unknown"), true);
            callAndPrint(client, "Missing required argument", Map.of(), true);
            callAndPrint(client, "Invalid argument type", Map.of("scenario", 42), true);
            System.out.println("Demo completed: valid call and expected errors handled.");
        } catch (Exception error) {
            System.err.println("MCP failure: could not complete communication with the server.");
            System.err.println("Details: " + error.getMessage());
            exitCode = 1;
        } finally {
            client.closeGracefully();
            System.out.println("Connection closed; server subprocess terminated.");
        }
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static McpSyncClient connectTo(String serverClass, Duration timeout) {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var parameters = ServerParameters.builder(java)
                .args("-Dorg.slf4j.simpleLogger.defaultLogLevel=off", "-cp", classpath, serverClass)
                .build();
        var transport = new StdioClientTransport(parameters, McpJsonDefaults.getMapper());
        return McpClient.sync(transport)
                .clientInfo(McpSchema.Implementation.builder("review-fixture-client", "0.1.0").build())
                .initializationTimeout(timeout)
                .requestTimeout(timeout)
                .build();
    }

    private static void callAndPrint(McpSyncClient client, String label, Map<String, Object> arguments,
                                     boolean expectedError) throws IOException {
        var request = McpSchema.CallToolRequest.builder(ReviewFixtureTool.NAME).arguments(arguments).build();
        System.out.println("\n" + label + " -> tools/call "
                + McpJsonDefaults.getMapper().writeValueAsString(request));
        var result = client.callTool(request);
        System.out.println("isError: " + Boolean.TRUE.equals(result.isError()));
        for (var content : result.content()) {
            if (content instanceof McpSchema.TextContent text) {
                System.out.println(text.text());
            }
        }
        if (Boolean.TRUE.equals(result.isError()) != expectedError) {
            throw new IllegalStateException("Unexpected result for example: " + label);
        }
    }
}
