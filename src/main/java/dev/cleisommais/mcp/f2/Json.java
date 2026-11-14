package dev.cleisommais.mcp.f2;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

public final class Json {
    private Json() {}

    public static String write(Object value) throws IOException {
        return McpJsonDefaults.getMapper().writeValueAsString(value);
    }

    public static <T> T read(String value, TypeRef<T> type) throws IOException {
        return McpJsonDefaults.getMapper().readValue(value, type);
    }

    public static Map<String, Object> object(String value) throws IOException {
        return read(value, new TypeRef<>() {});
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException("Expected a JSON object");
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object value) {
        if (!(value instanceof List<?>)) throw new IllegalArgumentException("Expected a JSON array");
        return (List<Object>) value;
    }

    public static String text(Map<String, Object> value, String key) {
        Object field = value.get(key);
        if (!(field instanceof String text)) throw new IllegalArgumentException("Expected string: " + key);
        return text;
    }

    static String boundedBody(InputStream input, int limit) throws IOException {
        try (input) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) throw new IOException("HTTP response exceeded the configured byte limit");
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
