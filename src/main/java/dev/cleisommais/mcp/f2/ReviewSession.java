package dev.cleisommais.mcp.f2;

import dev.cleisommais.mcp.f6.CampaignBudget;
import dev.cleisommais.mcp.f6.OpenAiModel;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** One review's explicit provider choice, shared call/deadline budget and optional verifier. */
final class ReviewSession implements AutoCloseable {
    private final Map<String, String> env;
    private final String platform, repository, mode;
    private final List<Map<String, Object>> attempts = new ArrayList<>();
    private final List<Object> providers = new ArrayList<>();
    private final java.util.concurrent.ExecutorService execution = Executors.newSingleThreadExecutor();
    private final long deadline;
    private int calls;
    private final int maxCalls, context, output;
    private final ReviewData.Model model;
    private VerifiedModel verified;

    ReviewSession(Map<String, String> env, String platform, String repository) throws Exception {
        this(env, platform, repository, null);
    }
    ReviewSession(Map<String, String> env, String platform, String repository, ReviewData.Model frozenPrimary) throws Exception {
        this.env = env; this.platform = platform; this.repository = repository;
        mode = value("REVIEW_MODE", "single");
        if (!List.of("single", "verified").contains(mode)) throw new IllegalArgumentException("REVIEW_MODE must be single or verified");
        maxCalls = integer("REVIEW_MAX_MODEL_CALLS", 8, 1, 100);
        int seconds = integer("REVIEW_TOTAL_SECONDS", 360, 1, 1800);
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        context = integer("OLLAMA_CONTEXT_LENGTH", 8192, 4096, 32768);
        output = integer("REVIEW_OUTPUT_TOKENS", 4096, 128, 4096);
        // Validate both roles before any model metadata/inference request is sent.
        for (String role : mode.equals("verified") ? List.of("MODEL_PROVIDER", "VERIFIER_PROVIDER") : List.of("MODEL_PROVIDER")) {
            String provider = value(role, "ollama");
            if (!List.of("ollama", "openai").contains(provider)) throw new IllegalArgumentException("Unsupported model provider");
            if (provider.equals("openai")) authorizeExternal(env, platform, repository);
        }
        var primary = frozenPrimary == null ? create(false) : frozenPrimary;
        if (frozenPrimary != null) providers.add(Map.of("role", "primary", "provider", "frozen-validated-proposals", "model", primary.name()));
        if (mode.equals("verified")) {
            int verifierOutput = integer("VERIFIER_OUTPUT_TOKENS", 1536, 128, 4096);
            verified = new VerifiedModel(primary, create(true), context - (verifierOutput <= 1536 ? 2500 : verifierOutput + 1000));
            model = verified;
        } else model = primary;
    }

    static void authorizeExternal(Map<String, String> env, String platform, String repository) {
        if (!"true".equals(env.get("EXTERNAL_INFERENCE_ENABLED"))) throw new SecurityException("External inference is disabled; local failure never enables it");
        String scope = platform + ":" + repository;
        if (!List.of(env.getOrDefault("EXTERNAL_ALLOWED_REPOSITORIES", "").split(",")).contains(scope)) {
            throw new SecurityException("Repository is outside the explicit external-data scope");
        }
    }

    private ReviewData.Model create(boolean verifier) throws Exception {
        String provider = value(verifier ? "VERIFIER_PROVIDER" : "MODEL_PROVIDER", "ollama");
        String role = verifier ? "verifier" : "primary";
        int seconds = integer("REVIEW_TIMEOUT_SECONDS", 180, 1, 600);
        int roleOutput = verifier ? integer("VERIFIER_OUTPUT_TOKENS", 1536, 128, 4096) : output;
        ReviewData.Model delegate;
        if (provider.equals("ollama")) {
            var local = new OllamaModel(URI.create(value("OLLAMA_BASE_URL", "http://localhost:11435/")),
                    value(verifier ? "VERIFIER_MODEL" : "OLLAMA_MODEL", verifier ? "qwen3:14b" : "gpt-oss:20b"),
                    context, Duration.ofSeconds(seconds), OllamaModel.parseThinking(value(verifier ? "VERIFIER_THINK" : "OLLAMA_THINK", verifier ? "false" : "medium")), roleOutput);
            providers.add(Map.of("role", role, "provider", provider, "metadata", local.metadata()));
            delegate = local;
        } else {
            String modelName = value(verifier ? "VERIFIER_MODEL" : "OPENAI_MODEL", "gpt-6-luna");
            if (!modelName.equals("gpt-6-luna")) throw new IllegalArgumentException("Only gpt-6-luna has a registered external pricing profile in this POC");
            var remote = OpenAiModel.configured(URI.create(value("OPENAI_BASE_URL", "https://api.openai.com/v1/")), modelName,
                    Path.of(value("OPENAI_API_KEY_FILE", "/run/secrets/openai-api-key")), value("OPENAI_REASONING", "medium"), roleOutput,
                    Duration.ofSeconds(seconds), new CampaignBudget(Path.of(value("EXTERNAL_BUDGET_JOURNAL", "/reports/external-campaign.json")),
                            new BigDecimal(value("EXTERNAL_MAX_COST_USD", "0"))), new BigDecimal("0.10"), new BigDecimal("0.50"));
            providers.add(Map.of("role", role, "provider", provider, "metadata", remote.metadata()));
            delegate = remote;
        }
        return limited(delegate, role, provider);
    }

    private ReviewData.Model limited(ReviewData.Model delegate, String role, String provider) {
        return new ReviewData.Model() {
            @Override public String name() { return delegate.name(); }
            @Override public ReviewData.Generation generate(String system, String input, Map<String, Object> schema) throws Exception {
                long remaining = deadline - System.nanoTime();
                if (calls >= maxCalls || remaining <= 0) throw new IOException("Review model-call or duration budget exhausted; no further call was sent");
                calls++;
                var attempt = new LinkedHashMap<String, Object>(); attempts.add(attempt);
                attempt.put("role", role); attempt.put("provider", provider); attempt.put("model", delegate.name());
                long started = System.nanoTime();
                var future = execution.submit(() -> delegate.generate(system, input, schema));
                try {
                    var result = future.get(remaining, TimeUnit.NANOSECONDS);
                    attempt.put("status", "completed"); attempt.put("promptTokens", result.promptTokens()); attempt.put("outputTokens", result.outputTokens());
                    return result;
                } catch (java.util.concurrent.ExecutionException error) {
                    attempt.put("status", "failed");
                    if (error.getCause() instanceof Exception cause) throw cause;
                    throw new IOException("Model call failed");
                } catch (Exception error) {
                    attempt.put("status", "failed-or-unknown"); throw error;
                } finally { future.cancel(true); attempt.put("elapsedMillis", (System.nanoTime() - started) / 1_000_000); }
            }
        };
    }
    Map<String, Object> review(ReviewData.Source source) throws Exception {
        var snapshot = source.snapshot();
        if (!platform.equals(snapshot.platform()) || !repository.equals(snapshot.repository())) throw new SecurityException("Review source is outside the configured data scope");
        var report = new ReviewEngine(context, integer("REVIEW_MAX_BATCHES", 4, 1, 100), value("REVIEW_PROMPT_PROFILE", "calibrated"), output).review(source, model);
        if (verified != null) verified.annotate(report);
        report.put("reviewMode", mode);
        report.put("inference", Map.of("providers", providers, "attempts", attempts, "maxCalls", maxCalls, "automaticFallback", false));
        return report;
    }
    private String value(String key, String fallback) { return env.getOrDefault(key, fallback); }
    private int integer(String key, int fallback, int min, int max) {
        int number = Integer.parseInt(value(key, Integer.toString(fallback)));
        if (number < min || number > max) throw new IllegalArgumentException("Invalid " + key);
        return number;
    }
    @Override public void close() { execution.shutdownNow(); }
}
