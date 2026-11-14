package dev.cleisommais.mcp.f2;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

public final class ManualReview {
    private ManualReview() {}

    public static void main(String[] args) {
        try {
            if (args.length == 1 && "--probe-tools".equals(args[0])) {
                if (!env("MODEL_PROVIDER", "ollama").equals("ollama")) throw new IllegalArgumentException("This native-tool probe is Ollama-only");
                var model = new OllamaModel(URI.create(env("OLLAMA_BASE_URL", "http://localhost:11435/")),
                        env("OLLAMA_MODEL", "gpt-oss:20b"), Integer.parseInt(env("OLLAMA_CONTEXT_LENGTH", "8192")),
                        Duration.ofSeconds(Integer.parseInt(env("REVIEW_TIMEOUT_SECONDS", "180"))),
                        OllamaModel.parseThinking(env("OLLAMA_THINK", "medium")), Integer.parseInt(env("REVIEW_OUTPUT_TOKENS", "4096")));
                System.out.println(Json.write(model.metadata()));
                boolean valid = model.probeTools();
                System.out.println("Validated native tool-call request: " + valid + "; no tool was executed by this probe.");
                if (!valid) System.exit(1);
                return;
            }
            if (args.length != 3 || !java.util.Set.of("github", "gitlab").contains(args[0])) {
                throw new IllegalArgumentException("Usage: github|gitlab namespace/repository CHANGE_NUMBER | --probe-tools");
            }
            dev.cleisommais.mcp.f5.GitLabAccess.validateRepository(args[1]);
            try (var session = new ReviewSession(System.getenv(), args[0], args[1]);
                 var source = source(args[0], args[1], Integer.parseInt(args[2]))) {
                var report = session.review(source);
                report.put("retrieval", source instanceof GatewayReviewSource gateway ? gateway.trace() : java.util.Map.of("mode", "direct-stdio"));
                var directory = Path.of(env("REPORT_DIR", Files.isDirectory(Path.of("/reports")) ? "/reports" : "reports"));
                Files.createDirectories(directory);
                String name = (args[0].equals("gitlab") ? "gitlab-" : "") + args[1].replace('/', '-') + "-pr" + args[2] + "-" + report.get("headSha").toString().substring(0, 12) + ".json";
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

    static ReviewData.Source source(String repository, int number) {
        return source("github", repository, number);
    }
    static ReviewData.Source source(String platform, String repository, int number) {
        return switch (env("MCP_SOURCE", "gateway")) {
            case "gateway" -> new GatewayReviewSource(URI.create(env("MCP_GATEWAY_URL", "http://gateway:8080")),
                    System.getenv("GATEWAY_TOKEN"), platform, repository, number, java.util.UUID.randomUUID());
            case "direct" -> new McpReviewSource(platform, repository, number,
                    platform.equals("gitlab") && System.getenv("GITLAB_READ_TOKEN") != null
                            ? java.util.Map.of("GITLAB_READ_TOKEN", System.getenv("GITLAB_READ_TOKEN")) : java.util.Map.of());
            default -> throw new IllegalArgumentException("MCP_SOURCE must be gateway or direct");
        };
    }

    private static String env(String name, String fallback) { return System.getenv().getOrDefault(name, fallback); }
}
