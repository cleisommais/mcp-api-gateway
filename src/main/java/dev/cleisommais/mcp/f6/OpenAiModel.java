package dev.cleisommais.mcp.f6;

import dev.cleisommais.mcp.f2.Json;
import dev.cleisommais.mcp.f2.ReviewData;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Explicit Responses API adapter; no automatic retries, fallback, tools, or remote MCP. */
public final class OpenAiModel implements ReviewData.Model {
    private final URI base;
    private final String model, key, reasoning;
    private final int outputTokens;
    private final Duration timeout;
    private final CampaignBudget budget;
    private final BigDecimal inputRate, outputRate;
    private final List<Map<String, Object>> attempts = new ArrayList<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public static OpenAiModel configured(URI base, String model, Path key, String reasoning, int outputTokens,
                                         Duration timeout, CampaignBudget budget, BigDecimal inputRate, BigDecimal outputRate) throws Exception {
        if (!"https".equals(base.getScheme()) || !"api.openai.com".equals(base.getHost()) || base.getPort() != -1
                || !"/v1/".equals(base.getPath()) || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null) {
            throw new IllegalArgumentException("The approved OpenAI endpoint is https://api.openai.com/v1/");
        }
        return new OpenAiModel(base, model, Files.readString(key).trim(), reasoning, outputTokens, timeout, budget, inputRate, outputRate);
    }

    // Loopback REST fixtures inject their endpoint here; runtime configuration uses configured().
    OpenAiModel(URI base, String model, String key, String reasoning, int outputTokens, Duration timeout,
                CampaignBudget budget, BigDecimal inputRate, BigDecimal outputRate) {
        if (!model.matches("[a-zA-Z0-9_.-]+") || !key.matches("[\\x21-\\x7E]+") || outputTokens < 128 || outputTokens > 4096
                || !List.of("none", "low", "medium", "high", "xhigh", "max").contains(reasoning)
                || inputRate.signum() <= 0 || outputRate.signum() <= 0 || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Invalid OpenAI model, credential reference, reasoning, rate or output limit");
        }
        this.base = base; this.model = model; this.key = key; this.reasoning = reasoning; this.outputTokens = outputTokens;
        this.timeout = timeout; this.budget = budget; this.inputRate = inputRate; this.outputRate = outputRate;
    }

    @Override public ReviewData.Generation generate(String system, String context, Map<String, Object> schema) throws Exception {
        var payload = Map.of("model", model, "instructions", system, "input", context, "store", false,
                "reasoning", Map.of("effort", reasoning), "max_output_tokens", outputTokens, "service_tier", "default",
                "text", Map.of("format", Map.of("type", "json_schema", "name", "pr_review", "strict", true, "schema", schema)));
        String body = Json.write(payload);
        // Byte-based input allowance plus framing margin; 1.25 covers the documented cache-write input premium.
        BigDecimal reserved = inputRate.multiply(new BigDecimal("1.25")).multiply(BigDecimal.valueOf(body.getBytes(StandardCharsets.UTF_8).length + 1024L))
                .add(outputRate.multiply(BigDecimal.valueOf(outputTokens))).movePointLeft(6);
        String reservation = budget.reserve(reserved);
        long started = System.nanoTime();
        Map<String, Object> usage = Map.of();
        String outcome = "unknown";
        try {
            var request = HttpRequest.newBuilder(base.resolve("responses")).timeout(timeout)
                    .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            String raw;
            try (var input = response.body()) {
                if (response.statusCode() != 200) { outcome = "http-" + response.statusCode(); throw new IOException("OpenAI HTTP " + response.statusCode() + "; no retry or fallback was attempted"); }
                byte[] bytes = input.readNBytes(256_001);
                if (bytes.length > 256_000) throw new IOException("OpenAI response exceeded the byte limit");
                raw = new String(bytes, StandardCharsets.UTF_8);
            }
            var result = Json.object(raw);
            usage = Json.map(result.get("usage"));
            if (!usage.containsKey("input_tokens") || !usage.containsKey("output_tokens")) throw new IOException("OpenAI usage is missing token totals");
            long input = number(usage, "input_tokens"), output = number(usage, "output_tokens");
            var inputDetails = Json.map(usage.getOrDefault("input_tokens_details", Map.of()));
            long cached = number(inputDetails, "cached_tokens"), writes = number(inputDetails, "cache_write_tokens");
            long reasoningTokens = number(Json.map(usage.getOrDefault("output_tokens_details", Map.of())), "reasoning_tokens");
            if (cached + writes > input || reasoningTokens > output) throw new IOException("Invalid OpenAI token accounting");
            BigDecimal estimate = inputRate.multiply(BigDecimal.valueOf(input - cached - writes))
                    .add(inputRate.multiply(new BigDecimal("0.1")).multiply(BigDecimal.valueOf(cached)))
                    .add(inputRate.multiply(new BigDecimal("1.25")).multiply(BigDecimal.valueOf(writes)))
                    .add(outputRate.multiply(BigDecimal.valueOf(output))).movePointLeft(6);
            var metric = new java.util.LinkedHashMap<String, Object>();
            metric.put("model", Json.text(result, "model")); metric.put("usage", usage);
            metric.put("estimatedUsd", estimate.toPlainString()); metric.put("reservedUsd", reserved.toPlainString());
            attempts.add(metric);
            if (estimate.compareTo(reserved) > 0) { outcome = "reservation-exceeded"; throw new IOException("Observed usage exceeded the reservation estimate; inspect the campaign journal before continuing"); }
            if (!"completed".equals(result.get("status"))) { outcome = "incomplete"; throw new IOException("OpenAI response was incomplete; usage remains recorded"); }
            if (!"default".equals(result.getOrDefault("service_tier", "default"))) throw new IOException("Unexpected OpenAI service tier");
            var texts = new ArrayList<String>();
            for (Object item : Json.list(result.get("output"))) {
                var message = Json.map(item);
                if ("reasoning".equals(message.get("type"))) continue;
                if (!"message".equals(message.get("type"))) throw new IOException("Unexpected OpenAI tool or output item");
                for (Object part : Json.list(message.get("content"))) {
                    var content = Json.map(part);
                    if (!"output_text".equals(content.get("type"))) { outcome = "refused-or-invalid"; throw new IOException("OpenAI returned a refusal or unsupported content"); }
                    texts.add(Json.text(content, "text"));
                }
            }
            if (texts.size() != 1) throw new IOException("Expected exactly one structured OpenAI text result");
            outcome = "completed";
            return new ReviewData.Generation(texts.getFirst(), (System.nanoTime() - started) / 1_000_000, input, output);
        } finally { budget.record(reservation, outcome, usage); }
    }

    private static long number(Map<String, Object> value, String name) throws IOException {
        Object raw = value.getOrDefault(name, 0);
        if (!(raw instanceof Integer || raw instanceof Long) || ((Number) raw).longValue() < 0) throw new IOException("Invalid OpenAI usage field");
        return ((Number) raw).longValue();
    }
    public Map<String, Object> metadata() { return Map.of("provider", "openai", "model", model, "endpoint", base.toString(),
            "reasoning", reasoning, "outputTokenBudget", outputTokens, "attempts", attempts,
            "costScope", "standard-tier estimate; reasoning already included in output tokens; not an invoice"); }
    @Override public String name() { return model; }
}
