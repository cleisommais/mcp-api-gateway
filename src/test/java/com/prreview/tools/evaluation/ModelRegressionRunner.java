package com.prreview.tools.evaluation;

import com.prreview.agent.service.ReviewService;
import com.prreview.common.config.SecretReader;
import com.prreview.common.json.Json;
import com.prreview.domain.model.ChangeSnapshot;
import com.prreview.mcp.client.GatewayMcpClient;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Explicit, public-only provider regression check; never publishes or executes repository code. */
public final class ModelRegressionRunner {
    private static final String REPOSITORY = "cleisommais/pr-test-repo";
    private static final Path ROOT = Path.of("/reports/model-regression");

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("capture")) {
            assertPublic();
            capture();
            return;
        }
        if (args.length != 2 || !args[0].equals("run") || !args[1].matches("[a-z0-9-]{1,40}")) {
            throw new IllegalArgumentException("Usage: capture | run LABEL");
        }
        // Even a locally edited capture cannot expand the external-data scope.
        assertPublic();
        String label = args[1];
        Path output = ROOT.resolve(label);
        if (Files.exists(output))
            throw new IllegalArgumentException("Run label already exists; preserve the baseline");
        Files.createDirectories(output);
        var results = new ArrayList<Map<String, Object>>();
        for (int number = 1; number <= 4; number++) {
            Path input = ROOT.resolve("fixtures/pr-" + number + ".json");
            try (var source = CapturedReviewFixture.FrozenSource.fromCapture(input)) {
                ChangeSnapshot snapshot = source.snapshot();
                if (!snapshot.platform().equals("github")
                        || !snapshot.repository().equals(REPOSITORY)
                        || snapshot.number() != number
                        || !snapshot.headRepository().equals(REPOSITORY)) {
                    throw new SecurityException(
                            "Only the fixed public regression fixtures are allowed");
                }
                try (var session = new ReviewService(System.getenv(), "github", REPOSITORY)) {
                    var report = Json.object(Json.write(session.review(source)));
                    report.put("evaluationOnly", true);
                    report.put("captureFile", input.toString());
                    report.put(
                            "replayScope",
                            "Frozen public data; current repository state is not revalidated and no"
                                + " comment is published");
                    Files.writeString(
                            output.resolve("pr-" + number + ".json"), Json.write(report) + "\n");
                    var result = grade(number, report);
                    result.put("baseSha", snapshot.baseSha());
                    result.put("headSha", snapshot.headSha());
                    result.put("model", report.get("model"));
                    result.put("promptVersion", report.get("promptVersion"));
                    results.add(result);
                    System.out.println(Json.write(result));
                }
            }
        }
        long passed =
                results.stream().filter(row -> Boolean.TRUE.equals(row.get("passed"))).count();
        Files.writeString(
                output.resolve("results.json"),
                Json.write(
                                Map.of(
                                        "label",
                                        label,
                                        "repository",
                                        REPOSITORY,
                                        "evaluationOnly",
                                        true,
                                        "passed",
                                        passed,
                                        "cases",
                                        results.size(),
                                        "results",
                                        results))
                        + "\n");
        System.out.println("Regression " + label + ": " + passed + "/" + results.size());
        if (passed != results.size()) System.exit(1);
    }

    private static void assertPublic() throws Exception {
        var response =
                java.net.http.HttpClient.newBuilder()
                        .connectTimeout(java.time.Duration.ofSeconds(10))
                        .build()
                        .send(
                                java.net.http.HttpRequest.newBuilder(
                                                URI.create(
                                                        "https://api.github.com/repos/"
                                                                + REPOSITORY))
                                        .header("User-Agent", "pr-review-regression")
                                        .timeout(java.time.Duration.ofSeconds(20))
                                        .GET()
                                        .build(),
                                java.net.http.HttpResponse.BodyHandlers.ofInputStream());
        String body = Json.boundedBody(response.body(), 256_000);
        if (response.statusCode() != 200)
            throw new SecurityException("Public repository status could not be verified");
        var metadata = Json.object(body);
        if (!Boolean.FALSE.equals(metadata.get("private"))
                || !REPOSITORY.equals(metadata.get("full_name"))) {
            throw new SecurityException(
                    "Regression requests require the configured repository to be public");
        }
    }

    private static void capture() throws Exception {
        Path directory = ROOT.resolve("fixtures");
        Files.createDirectories(directory);
        for (int number = 1; number <= 4; number++) {
            Path path = directory.resolve("pr-" + number + ".json");
            if (Files.exists(path)) {
                System.out.println("Preserved capture for PR " + number);
                continue;
            }
            try (var source =
                    new GatewayMcpClient(
                            URI.create(
                                    System.getenv()
                                            .getOrDefault(
                                                    "MCP_GATEWAY_URL", "http://mcp-gateway:8080")),
                            SecretReader.required("MCP_GATEWAY_TOKEN"),
                            "github",
                            REPOSITORY,
                            number,
                            UUID.randomUUID())) {
                var snapshot = source.snapshot();
                var contents = new LinkedHashMap<String, String>();
                var errors = new LinkedHashMap<String, String>();
                for (var file : snapshot.files()) {
                    if (file.status().equals("removed")) continue;
                    try {
                        contents.put(
                                file.path(), source.fileAtCommit(snapshot.headSha(), file.path()));
                    } catch (Exception unavailable) {
                        errors.put(file.path(), unavailable.getClass().getSimpleName());
                    }
                }
                if (!source.isCurrent(snapshot))
                    throw new IllegalStateException("Fixture changed during capture");
                Files.writeString(
                        path,
                        Json.write(
                                        Map.of(
                                                "snapshot",
                                                snapshot,
                                                "contents",
                                                contents,
                                                "contextErrors",
                                                errors,
                                                "capturedAt",
                                                Instant.now().toString()))
                                + "\n");
                System.out.println("Captured public PR " + number + " at " + snapshot.headSha());
            }
        }
    }

    static Map<String, Object> grade(int number, Map<String, Object> report) {
        var findings = Json.list(report.get("findings"));
        String severity = number == 3 ? "MEDIUM" : "HIGH";
        int count = number == 2 ? 0 : 1;
        boolean complete =
                Boolean.TRUE.equals(report.get("enumerationComplete"))
                        && Json.list(report.get("errors")).isEmpty()
                        && Json.list(report.get("rejectedFindings")).isEmpty()
                        && Json.list(report.get("coverage")).stream()
                                .map(Json::map)
                                .allMatch(
                                        row ->
                                                List.of("analyzed", "excluded")
                                                        .contains(row.get("status")));
        boolean correct = findings.size() == count;
        if (correct && count == 1) {
            var finding = Json.map(findings.getFirst());
            String explanation = Json.text(finding, "explanation").replace(",", "");
            correct =
                    severity.equals(finding.get("severity"))
                            && (number == 3
                                            ? "src/demo/Checkout.java"
                                            : "src/demo/ArrayTotals.java")
                                    .equals(finding.get("file"))
                            && ((Number) finding.get("line")).intValue()
                                    == (number == 3 ? 10 : number == 4 ? 9 : 8)
                            && (number == 3
                                    ? explanation.matches("(?s).*\\b12\\b.*")
                                            && explanation.contains("1250")
                                    : explanation.matches("(?is).*(bounds|crash|exception).*"));
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("number", number);
        result.put("completeCoverage", complete);
        result.put("expectedCount", count);
        result.put("expectedSeverity", count == 0 ? "none" : severity);
        result.put("findings", findings);
        result.put("passed", complete && correct);
        return result;
    }
}
