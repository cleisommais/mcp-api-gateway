package dev.cleisommais.mcp.f2;

import dev.cleisommais.mcp.f4.JobStore;
import dev.cleisommais.mcp.f4.WebhookPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Local administrative fixture driver, not a public endpoint or evidence of a GitHub delivery. */
public final class F7Lab {
    private F7Lab() {}
    public static void main(String[] args) throws Exception {
        String schema = System.getenv().getOrDefault("DATABASE_SCHEMA", "");
        if (!schema.matches("f7_lab_[a-z0-9_]{1,40}")) throw new IllegalArgumentException("F7 lab requires its own f7_lab_ schema");
        var store = JobStore.environment();
        if (args.length == 2 && args[0].equals("seed")) {
            switch (args[1]) {
                case "concurrency" -> { seed(store, false, 2); seed(store, false, 3); seed(store, true, 1); }
                case "restart", "unavailable", "output-limit" -> seed(store, false, 1);
                case "timeout" -> seed(store, false, 3);
                case "injection" -> seed(store, false, 4);
                case "partial" -> seed(store, false, 2);
                default -> throw new IllegalArgumentException("Unknown F7 scenario");
            }
        } else if (args.length != 1 || !args[0].equals("inspect")) throw new IllegalArgumentException("Usage: seed SCENARIO | inspect");
        var rows = new ArrayList<Map<String, Object>>();
        for (var job : store.list()) {
            var row = new LinkedHashMap<String, Object>();
            row.put("id", job.id().toString()); row.put("repository", job.repository()); row.put("number", job.number());
            row.put("state", job.state()); row.put("attempts", job.attempts()); row.put("publicationState", job.publicationState());
            if (job.report() != null) {
                var report = Json.object(job.report());
                row.put("status", report.get("status")); row.put("current", report.get("current"));
                row.put("enumeratedFiles", report.get("enumeratedFiles")); row.put("findings", Json.list(report.get("findings")).size());
                row.put("coverageCounts", Json.list(report.get("coverage")).stream().map(Json::map)
                        .collect(java.util.stream.Collectors.groupingBy(item -> item.get("status"), java.util.stream.Collectors.counting())));
                row.put("usage", report.get("usage")); row.put("contextBudget", report.get("contextBudget"));
                row.put("inferenceAdmission", report.get("inferenceAdmission"));
                row.put("modelAttempts", Json.map(report.get("inference")).get("attempts"));
                row.put("errorCount", Json.list(report.get("errors")).size());
            }
            rows.add(row);
        }
        var result = Map.of("schema", schema, "at", Instant.now().toString(), "jobs", rows);
        Path output = Path.of("/reports/f7", schema + ".json"); Files.createDirectories(output.getParent());
        Files.writeString(output, Json.write(result) + "\n"); System.out.println(Json.write(result));
    }
    private static void seed(JobStore store, boolean privateRepository, int number) throws Exception {
        String repository = "cleisommais/pr-test-repo" + (privateRepository ? "-private" : "");
        long id = privateRepository ? 1457261925L : 1456683306L;
        try (var source = ManualReview.source("github", repository, number)) {
            var snapshot = source.snapshot();
            if (!"open".equals(snapshot.state()) || snapshot.draft() || !"main".equals(snapshot.baseBranch())) {
                throw new IllegalStateException("Fixture PR is no longer eligible");
            }
            var receipt = store.enqueue(new WebhookPolicy.Event(UUID.randomUUID().toString(), "f".repeat(64), "pull_request", "opened",
                    id, repository, number, Long.parseLong(System.getenv("GITHUB_INSTALLATION_ID")), snapshot.baseSha(), snapshot.headSha(),
                    snapshot.baseBranch(), Instant.now(), true, "local-f7-fixture"));
            System.out.println("Enqueued local fixture " + receipt.jobId() + "; duplicate=" + receipt.duplicate());
        }
    }
}
