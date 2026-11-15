package com.prreview.mcpgateway.config;

import io.modelcontextprotocol.spec.McpSchema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** The reviewed downstream contract is pinned independently of discovery. */
public final class ReviewToolCatalog {
    private ReviewToolCatalog() {}

    static final Map<String, Object> EMPTY =
            Map.of("type", "object", "properties", Map.of(), "additionalProperties", false);
    static final Map<String, Object> FILE =
            Map.of(
                    "type",
                    "object",
                    "properties",
                    Map.of(
                            "sha", Map.of("type", "string", "pattern", "^[a-f0-9]{40}$"),
                            "path", Map.of("type", "string", "minLength", 1)),
                    "required",
                    List.of("sha", "path"),
                    "additionalProperties",
                    false);
    private static final Map<String, String> DESCRIPTIONS =
            Map.of(
                    "get_change_snapshot",
                            "Read metadata and all available file patches, pinning base/head SHAs.",
                    "get_file_at_commit",
                            "Read a text file from this PR at its pinned head SHA only.",
                    "get_change_state",
                            "Check whether the pinned base/head/state are still current.");

    public static void validate(List<McpSchema.Tool> tools) {
        if (tools.size() != 3
                || !tools.stream()
                        .map(McpSchema.Tool::name)
                        .collect(Collectors.toSet())
                        .equals(DESCRIPTIONS.keySet())) {
            throw new SecurityException("Downstream catalog changed");
        }
        for (var tool : tools) {
            var expected = "get_file_at_commit".equals(tool.name()) ? FILE : EMPTY;
            if (!expected.equals(tool.inputSchema())
                    || !DESCRIPTIONS.get(tool.name()).equals(tool.description())) {
                throw new SecurityException("Downstream schema or description changed");
            }
        }
    }

    public static Map<String, Map<String, Object>> schemas() {
        return schemas(Set.of("github", "gitlab"));
    }

    public static Map<String, Map<String, Object>> schemas(Set<String> platforms) {
        var identity =
                Map.<String, Object>of(
                        "jobId",
                        Map.of("type", "string", "format", "uuid"),
                        "repository",
                        Map.of("type", "string", "minLength", 3),
                        "number",
                        Map.of("type", "integer", "minimum", 1));
        var result = new LinkedHashMap<String, Map<String, Object>>();
        for (String platform : platforms) {
            if (!Set.of("github", "gitlab").contains(platform))
                throw new IllegalArgumentException("Unsupported platform");
            for (String operation :
                    List.of(
                            "get_change_snapshot",
                            "get_file_at_commit",
                            "get_change_state",
                            "close_review")) {
                String tool = platform + "." + operation;
                var properties = new LinkedHashMap<>(identity);
                if (!tool.endsWith("get_change_snapshot"))
                    properties.put("sha", FILE_PROPERTIES_SHA());
                if (tool.endsWith("get_file_at_commit"))
                    properties.put("path", Map.of("type", "string", "minLength", 1));
                result.put(
                        tool,
                        Map.of(
                                "type",
                                "object",
                                "properties",
                                properties,
                                "required",
                                List.copyOf(properties.keySet()),
                                "additionalProperties",
                                false));
            }
        }
        return Map.copyOf(result);
    }

    private static Map<String, Object> FILE_PROPERTIES_SHA() {
        return Map.of("type", "string", "pattern", "^[a-f0-9]{40}$");
    }
}
