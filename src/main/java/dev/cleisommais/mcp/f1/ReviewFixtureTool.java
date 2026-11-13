package dev.cleisommais.mcp.f1;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Read-only tool that returns synthetic data without accessing a real repository. */
public final class ReviewFixtureTool {
    public static final String NAME = "get_review_fixture";
    public static final String SCENARIO = "off-by-one";

    private ReviewFixtureTool() {
    }

    public static McpServerFeatures.SyncToolSpecification specification() throws IOException {
        // The fixture is bundled in the JAR; the argument is not a file path.
        String diff;
        try (var input = ReviewFixtureTool.class.getResourceAsStream("/fixtures/off-by-one.diff")) {
            if (input == null) {
                throw new IOException("Fixture off-by-one.diff was not found on the classpath");
            }
            diff = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        var fixture = new ReviewFixture(SCENARIO, "demo/review-fixtures",
                "1111111111111111111111111111111111111111", "src/TotalCalculator.java", diff);

        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of("scenario", Map.of(
                        "type", "string", "minLength", 1,
                        "description", "Fixture identifier; available scenario: off-by-one")),
                "required", List.of("scenario"),
                "additionalProperties", false);

        var tool = McpSchema.Tool.builder(NAME, schema)
                .description("Returns a known synthetic diff for learning MCP tool calls without AI.")
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    // The SDK validates the schema; scenario existence is a tool-level rule.
                    if (!SCENARIO.equals(request.arguments().get("scenario"))) {
                        return McpSchema.CallToolResult.builder()
                                .isError(true)
                                .addTextContent("Scenario not found. Use: off-by-one")
                                .build();
                    }
                    return McpSchema.CallToolResult.builder()
                            .addTextContent(fixture.diff())
                            .structuredContent(fixture)
                            .build();
                })
                .build();
    }

    public record ReviewFixture(String scenario, String repository, String commit,
                                String file, String diff) {
    }
}
