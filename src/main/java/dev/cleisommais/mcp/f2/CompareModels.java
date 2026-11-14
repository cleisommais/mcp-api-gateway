package dev.cleisommais.mcp.f2;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dev.cleisommais.mcp.f2.ReviewData.*;

/** Public-data experiment: change one variable at a time, preserving the exact model inputs. */
public final class CompareModels {
    private CompareModels() {}
    record Input(String system, String context, Map<String, Object> schema, String sha256) {}

    public static void main(String[] args) throws Exception {
        if (args.length < 3 || !"github".equals(args[0])) throw new IllegalArgumentException("Usage: github owner/repository PR_NUMBER [PR_NUMBER ...]");
        var models = List.of(System.getenv().getOrDefault("COMPARE_MODELS", "qwen3.5:9b,qwen3:14b").split(","));
        var profiles = List.of(System.getenv().getOrDefault("COMPARE_PROFILES", "original,calibrated").split(","));
        var root = Path.of(System.getenv().getOrDefault("REPORT_DIR", "/reports"), "comparison");
        int context = Integer.parseInt(System.getenv().getOrDefault("OLLAMA_CONTEXT_LENGTH", "8192"));
        int outputTokens = Integer.parseInt(System.getenv().getOrDefault("COMPARE_OUTPUT_TOKENS", "1536"));
        Object thinking = OllamaModel.parseThinking(System.getenv().getOrDefault("COMPARE_THINK", "false"));
        String replayDirectory = System.getenv("REPLAY_INPUT_DIR");
        for (int i = 2; i < args.length; i++) {
            int number = Integer.parseInt(args[i]);
            try (var source = replayDirectory == null ? new FrozenSource(ManualReview.source(args[1], number))
                    : FrozenSource.fromCapture(Path.of(replayDirectory, "inputs-pr-" + number + ".json"))) {
                if (!args[1].equals(source.snapshot().repository()) || number != source.snapshot().number()) {
                    throw new IllegalArgumentException("Captured resource does not match the requested evaluation");
                }
                String capturedAt = source.capturedAt;
                var allInputs = new LinkedHashMap<String, List<Input>>();
                for (String profile : profiles) {
                    var inputs = new ArrayList<Input>();
                    allInputs.put(profile, inputs);
                    for (String modelName : models) {
                        if (!modelName.matches("[A-Za-z0-9_.:/-]+")) throw new IllegalArgumentException("Invalid comparison model name");
                        var model = new OllamaModel(URI.create(System.getenv().getOrDefault("OLLAMA_BASE_URL", "http://localhost:11435/")),
                                modelName, context, Duration.ofSeconds(Integer.parseInt(System.getenv().getOrDefault("REVIEW_TIMEOUT_SECONDS", "180"))),
                                thinking, outputTokens);
                        var metadata = model.metadata();
                        var controlled = new ControlledModel(model, inputs, models.getFirst().equals(modelName));
                        var report = new ReviewEngine(context, Integer.parseInt(System.getenv().getOrDefault("COMPARE_MAX_BATCHES", "4")), profile, outputTokens).review(source, controlled);
                        report.put("inference", metadata);
                        report.put("evaluationOnly", true);
                        report.put("retrieval", replayDirectory == null ? "captured-through-gateway" : "offline-captured-fixture; current PR state not rechecked");
                        report.put("snapshotCapturedAt", capturedAt);
                        report.put("evaluationStartedAt", Instant.now().toString());
                        report.put("inputHashes", inputs.stream().map(Input::sha256).toList());
                        report.put("comparisonControl", "Same frozen snapshot, supplemental context, prompt, schema, options and validation; model changes within profile");
                        var directory = root.resolve(profile).resolve(modelName.replaceAll("[^A-Za-z0-9_.-]", "-"));
                        Files.createDirectories(directory);
                        Files.writeString(directory.resolve("pr-" + number + ".json"), Json.write(report) + "\n");
                        System.out.println("PR " + number + " " + profile + " " + modelName + ": " + report.get("status")
                                + ", findings=" + ((List<?>) report.get("findings")).size() + ", errors=" + report.get("errors"));
                        source.freeze();
                    }
                }
                var fixture = Map.of("snapshot", source.snapshot(), "contents", source.contents, "contextErrors", source.errors, "currentAtCapture", source.current,
                        "capturedAt", capturedAt, "inputs", allInputs);
                Files.createDirectories(root);
                Files.writeString(root.resolve("inputs-pr-" + number + ".json"), Json.write(fixture) + "\n");
            }
        }
    }

    static final class ControlledModel implements Model {
        private final Model delegate;
        private final List<Input> inputs;
        private final boolean capture;
        private int index;
        ControlledModel(Model delegate, List<Input> inputs, boolean capture) { this.delegate = delegate; this.inputs = inputs; this.capture = capture; }
        @Override public Generation generate(String system, String context, Map<String, Object> schema) throws Exception {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((system + "\n" + context + "\n" + Json.write(schema)).getBytes(StandardCharsets.UTF_8)));
            if (capture) inputs.add(new Input(system, context, schema, hash));
            else {
                if (index >= inputs.size() || !hash.equals(inputs.get(index).sha256())) throw new IllegalStateException("Comparison input changed");
                var original = inputs.get(index);
                system = original.system(); context = original.context(); schema = original.schema();
            }
            index++;
            return delegate.generate(system, context, schema);
        }
        @Override public String name() { return delegate.name(); }
    }

    static final class FrozenSource implements Source {
        private final Source delegate;
        private Snapshot snapshot;
        private final Map<String, String> contents = new LinkedHashMap<>();
        private final Map<String, String> errors = new LinkedHashMap<>();
        private boolean frozen;
        private boolean current;
        private String capturedAt = Instant.now().toString();
        FrozenSource(Source delegate) { this.delegate = delegate; }
        static FrozenSource fromCapture(Path path) throws Exception {
            var record = Json.object(Files.readString(path));
            var result = new FrozenSource(null);
            var snapshot = new LinkedHashMap<>(Json.map(record.get("snapshot")));
            // F3 captures predate F4's draft/target-branch fields; replay never establishes current state.
            snapshot.putIfAbsent("draft", false); snapshot.putIfAbsent("baseBranch", "");
            result.snapshot = Json.read(Json.write(snapshot), new io.modelcontextprotocol.json.TypeRef<Snapshot>() {});
            Json.map(record.get("contents")).forEach((key, value) -> result.contents.put(key, (String) value));
            Json.map(record.get("contextErrors")).forEach((key, value) -> result.errors.put(key, (String) value));
            result.frozen = true;
            result.current = false;
            result.capturedAt = Json.text(record, "capturedAt");
            return result;
        }
        @Override public Snapshot snapshot() throws Exception { if (snapshot == null) snapshot = delegate.snapshot(); return snapshot; }
        @Override public String fileAtCommit(String sha, String path) throws Exception {
            if (!snapshot().headSha().equals(sha)) throw new SecurityException("Replay requires pinned SHA");
            if (errors.containsKey(path)) throw new IOException(errors.get(path));
            if (contents.containsKey(path)) return contents.get(path);
            if (frozen) throw new IOException("Context was not present in captured input");
            try { String content = delegate.fileAtCommit(sha, path); contents.put(path, content); return content; }
            catch (Exception error) { errors.put(path, error.getClass().getSimpleName()); throw error; }
        }
        @Override public boolean isCurrent(Snapshot snapshot) throws Exception {
            if (!frozen) current = delegate.isCurrent(snapshot);
            return current;
        }
        void freeze() { if (!frozen) { frozen = true; delegate.close(); } }
        @Override public void close() { freeze(); }
    }
}
