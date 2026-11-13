package dev.cleisommais.mcp.f2;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

public final class ManualReview {
    private ManualReview() {}

    public static void main(String[] args) {
        try {
            var model = new OllamaModel(URI.create(env("OLLAMA_BASE_URL", "http://localhost:11435/")),
                    env("OLLAMA_MODEL", "qwen3.5:9b"), Integer.parseInt(env("OLLAMA_CONTEXT_LENGTH", "8192")),
                    Duration.ofSeconds(Integer.parseInt(env("REVIEW_TIMEOUT_SECONDS", "180"))));
            if (args.length == 1 && "--probe-tools".equals(args[0])) {
                System.out.println(Json.write(model.metadata()));
                boolean valid = model.probeTools();
                System.out.println("Validated native tool-call request: " + valid + "; no tool was executed by this probe.");
                if (!valid) System.exit(1);
                return;
            }
            if (args.length != 3 || !"github".equals(args[0])) {
                throw new IllegalArgumentException("Usage: github owner/repository PR_NUMBER | --probe-tools");
            }
            var metadata = model.metadata();
            try (var source = new McpReviewSource(args[1], Integer.parseInt(args[2]))) {
                var report = new ReviewEngine(Integer.parseInt(env("OLLAMA_CONTEXT_LENGTH", "8192")),
                        Integer.parseInt(env("REVIEW_MAX_BATCHES", "4"))).review(source, model);
                report.put("inference", metadata);
                var directory = Path.of(env("REPORT_DIR", Files.isDirectory(Path.of("/reports")) ? "/reports" : "reports"));
                Files.createDirectories(directory);
                String name = args[1].replace('/', '-') + "-pr" + args[2] + "-" + report.get("headSha").toString().substring(0, 12) + ".json";
                var path = directory.resolve(name);
                Files.writeString(path, Json.write(report) + "\n");
                System.out.println("Report: " + path + "; status=" + report.get("status") + "; files=" + report.get("enumeratedFiles")
                        + "; findings=" + ((java.util.List<?>) report.get("findings")).size());
                if (!"complete".equals(report.get("status"))) throw new IllegalStateException("Review is incomplete; inspect the saved report");
            }
        } catch (Exception error) {
            System.err.println("Review failed: " + error.getClass().getSimpleName() + ": " + error.getMessage());
            System.exit(1);
        }
    }

    private static String env(String name, String fallback) { return System.getenv().getOrDefault(name, fallback); }
}
