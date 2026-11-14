package dev.cleisommais.mcp.f2;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Frozen public fixtures only. Evaluation reports cannot establish current PR state or publish. */
public final class F6Evaluation {
    private F6Evaluation() {}
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !java.util.Set.of("single", "verified").contains(args[0])) {
            throw new IllegalArgumentException("Usage: single|verified");
        }
        var environment = new java.util.HashMap<>(System.getenv()); environment.put("REVIEW_MODE", args[0]);
        Path captures = Path.of(environment.getOrDefault("REPLAY_INPUT_DIR", "/fixtures"));
        Path output = Path.of(environment.getOrDefault("REPORT_DIR", "/reports"), args[0]);
        Files.createDirectories(output);
        for (int number = 1; number <= 4; number++) {
            var baseline = args[0].equals("verified") && !"false".equals(environment.get("FREEZE_PRIMARY"))
                    ? Json.object(Files.readString(output.getParent().resolve("single/pr-" + number + ".json"))) : null;
            try (var source = CompareModels.FrozenSource.fromCapture(captures.resolve("inputs-pr-" + number + ".json"));
                 var session = new ReviewSession(environment, "github", "cleisommais/pr-test-repo", baseline == null ? null : frozen(baseline))) {
                if (baseline != null && (!source.snapshot().headSha().equals(baseline.get("headSha"))
                        || !Json.list(baseline.get("errors")).isEmpty() || !Json.list(baseline.get("rejectedFindings")).isEmpty())) {
                    throw new IllegalArgumentException("Frozen primary baseline must match the snapshot with valid output");
                }
                var report = session.review(source);
                report.put("evaluationOnly", true); report.put("retrieval", "frozen public capture; current state not checked");
                report.put("primaryControl", baseline == null ? "fresh generation" : "replay of the single run's validated proposals; no primary inference; timings cover verifier work only");
                Files.writeString(output.resolve("pr-" + number + ".json"), Json.write(report) + "\n");
                System.out.println("PR " + number + "; mode=" + args[0] + "; snapshotStatus=" + report.get("status")
                        + "; findings=" + Json.list(report.get("findings")).size() + "; errors=" + Json.list(report.get("errors")).size());
            }
        }
    }
    private static ReviewData.Model frozen(Map<String, Object> baseline) {
        return new ReviewData.Model() {
            private int batch;
            @Override public String name() { return baseline.get("model").toString(); }
            @Override public ReviewData.Generation generate(String system, String input, Map<String, Object> schema) throws Exception {
                var paths = Json.list(Json.object(input).get("files")).stream().map(Json::map).map(row -> row.get("path")).toList();
                var findings = Json.list(baseline.get("findings")).stream().map(Json::map).filter(row -> paths.contains(row.get("file"))).toList();
                var summaries = Json.list(baseline.get("summaries"));
                if (batch >= summaries.size()) throw new IllegalArgumentException("Frozen primary batching changed");
                return new ReviewData.Generation(Json.write(Map.of("summary", summaries.get(batch++), "findings", findings)), 0, 0, 0);
            }
        };
    }
}
