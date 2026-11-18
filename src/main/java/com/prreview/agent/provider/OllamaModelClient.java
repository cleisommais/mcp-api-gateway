package com.prreview.agent.provider;

import com.prreview.common.json.Json;
import com.prreview.domain.model.ModelGeneration;
import com.prreview.domain.port.ReviewModel;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Direct native inference API; MCP is used separately for repository tools. */
public final class OllamaModelClient implements ReviewModel {
    public static final class IncompleteGeneration extends IOException {
        public final long durationMillis, promptTokens, outputTokens;

        IncompleteGeneration(long durationMillis, long promptTokens, long outputTokens) {
            super("Incomplete model generation");
            this.durationMillis = durationMillis;
            this.promptTokens = promptTokens;
            this.outputTokens = outputTokens;
        }
    }

    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final URI base;
    private final String model;
    private final int context;
    private final Duration timeout;
    private final Object thinking;
    private final int outputTokens;

    public OllamaModelClient(URI base, String model, int context, Duration timeout) {
        this(base, model, context, timeout, false, 1536);
    }

    public OllamaModelClient(
            URI base,
            String model,
            int context,
            Duration timeout,
            Object thinking,
            int outputTokens) {
        if (!List.of("http", "https").contains(base.getScheme())
                || base.getHost() == null
                || base.getUserInfo() != null
                || base.getQuery() != null
                || base.getFragment() != null) {
            throw new IllegalArgumentException(
                    "Expected an Ollama HTTP endpoint without credentials");
        }
        if (context < 4096 || context > 32768)
            throw new IllegalArgumentException("Context must be 4096..32768");
        if (outputTokens < 128 || outputTokens > 16384 || outputTokens > context - 1000)
            throw new IllegalArgumentException("Invalid output budget");
        if (!(thinking instanceof Boolean)
                && !(thinking instanceof String level
                        && List.of("low", "medium", "high").contains(level))) {
            throw new IllegalArgumentException(
                    "Thinking must be true, false, low, medium, or high");
        }
        this.base = base;
        this.model = model;
        this.context = context;
        this.timeout = timeout;
        this.thinking = thinking;
        this.outputTokens = outputTokens;
    }

    public Map<String, Object> metadata() throws Exception {
        var version = request("api/version", null);
        var show = request("api/show", Map.of("model", model));
        Object supportedThinking = show.getOrDefault("thinking", Map.of());
        if (thinking instanceof String
                && supportedThinking instanceof Map<?, ?> values
                && values.get("values") instanceof List<?> levels
                && !levels.contains(thinking))
            throw new IllegalArgumentException(
                    "The model does not support the selected reasoning level");
        var tags = request("api/tags", null);
        String digest =
                Json.list(tags.get("models")).stream()
                        .map(Json::map)
                        .filter(entry -> model.equals(entry.get("name")))
                        .map(entry -> String.valueOf(entry.get("digest")))
                        .findFirst()
                        .orElse("unknown");
        return Map.of(
                "runtime",
                version.get("version"),
                "model",
                model,
                "digest",
                digest,
                "details",
                show.getOrDefault("details", Map.of()),
                "contextRequested",
                context,
                "thinking",
                thinking,
                "outputTokenBudget",
                outputTokens,
                "capabilities",
                show.getOrDefault("capabilities", List.of()),
                "supportedThinking",
                supportedThinking);
    }

    @Override
    public ModelGeneration generate(String system, String input, Map<String, Object> schema)
            throws Exception {
        var response =
                request(
                        "api/chat",
                        Map.of(
                                "model",
                                model,
                                "stream",
                                false,
                                "think",
                                thinking,
                                "messages",
                                List.of(
                                        Map.of(
                                                "role",
                                                "system",
                                                "content",
                                                system
                                                        + "\nRequired output JSON schema:\n"
                                                        + Json.write(schema)),
                                        Map.of("role", "user", "content", input)),
                                "format",
                                schema,
                                "options",
                                Map.of(
                                        "temperature",
                                        0,
                                        "num_ctx",
                                        context,
                                        "num_predict",
                                        outputTokens)));
        if (!Boolean.TRUE.equals(response.get("done"))
                || "length".equals(response.get("done_reason"))) {
            throw new IncompleteGeneration(
                    number(response, "total_duration") / 1_000_000,
                    number(response, "prompt_eval_count"),
                    number(response, "eval_count"));
        }
        return new ModelGeneration(
                Json.text(Json.map(response.get("message")), "content"),
                number(response, "total_duration") / 1_000_000,
                number(response, "prompt_eval_count"),
                number(response, "eval_count"));
    }

    public boolean probeTools() throws Exception {
        var schema =
                Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of(
                                "path",
                                Map.of(
                                        "type",
                                        "string",
                                        "enum",
                                        List.of("src/demo/ArrayTotals.java"))),
                        "required",
                        List.of("path"),
                        "additionalProperties",
                        false);
        var result =
                request(
                        "api/chat",
                        Map.of(
                                "model",
                                model,
                                "stream",
                                false,
                                "think",
                                false,
                                "messages",
                                List.of(
                                        Map.of(
                                                "role",
                                                "user",
                                                "content",
                                                "Call read_file exactly once with path"
                                                    + " src/demo/ArrayTotals.java. Do not answer"
                                                    + " directly.")),
                                "tools",
                                List.of(
                                        Map.of(
                                                "type",
                                                "function",
                                                "function",
                                                Map.of(
                                                        "name",
                                                        "read_file",
                                                        "description",
                                                        "Read the named fixture file",
                                                        "parameters",
                                                        schema))),
                                "options",
                                Map.of("temperature", 0, "num_ctx", context, "num_predict", 256)));
        var message = Json.map(result.get("message"));
        var calls = Json.list(message.getOrDefault("tool_calls", List.of()));
        if (calls.size() != 1) return false;
        var function = Json.map(Json.map(calls.getFirst()).get("function"));
        return "read_file".equals(function.get("name"))
                && Map.of("path", "src/demo/ArrayTotals.java").equals(function.get("arguments"));
    }

    private Map<String, Object> request(String path, Object payload) throws Exception {
        var builder = HttpRequest.newBuilder(base.resolve(path)).timeout(timeout);
        if (payload == null) builder.GET();
        else
            builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.write(payload)));
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        String body = Json.boundedBody(response.body(), 256_000);
        if (response.statusCode() != 200)
            throw new IOException(
                    "Ollama HTTP " + response.statusCode() + "; check runtime/model availability");
        return Json.object(body);
    }

    private static long number(Map<String, Object> value, String key) {
        return value.get(key) instanceof Number number ? number.longValue() : 0;
    }

    @Override
    public String name() {
        return model;
    }

    public static Object parseThinking(String value) {
        return switch (value) {
            case "true" -> true;
            case "false" -> false;
            case "low", "medium", "high" -> value;
            default ->
                    throw new IllegalArgumentException(
                            "Thinking must be true, false, low, medium, or high");
        };
    }
}
