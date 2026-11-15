package com.prreview.agent.service;

import static org.junit.jupiter.api.Assertions.*;

import com.prreview.common.json.Json;
import com.prreview.domain.model.ChangeSnapshot;
import com.prreview.domain.model.FileChange;
import com.prreview.domain.model.ModelGeneration;
import com.prreview.domain.port.ChangeSource;
import com.prreview.domain.port.ReviewModel;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class CandidateVerifierTest {
    private static final String PATCH = "@@ -1 +1 @@\n-old();\n+broken();";
    private final Map<String, Object> finding =
            Map.of(
                    "file",
                    "A.java",
                    "line",
                    1,
                    "evidence",
                    "broken();",
                    "severity",
                    "HIGH",
                    "explanation",
                    "Concrete failure");

    private ReviewModel model(String output) {
        return new ReviewModel() {
            @Override
            public String name() {
                return "synthetic";
            }

            @Override
            public ModelGeneration generate(
                    String system, String input, Map<String, Object> schema) {
                return new ModelGeneration(output, 1, 10, 5);
            }
        };
    }

    private ChangeSource source() {
        var snapshot =
                new ChangeSnapshot(
                        "github",
                        "owner/repo",
                        1,
                        "title",
                        "description",
                        "a".repeat(40),
                        "b".repeat(40),
                        "owner/repo",
                        "open",
                        1,
                        true,
                        List.of(new FileChange("A.java", "modified", PATCH, true, 1, 1, "source")));
        return new ChangeSource() {
            @Override
            public ChangeSnapshot snapshot() {
                return snapshot;
            }

            @Override
            public String fileAtCommit(String sha, String path) {
                return "broken();";
            }

            @Override
            public boolean isCurrent(ChangeSnapshot ignored) {
                return true;
            }

            @Override
            public void close() {}
        };
    }

    private String primary(List<?> findings) throws Exception {
        return Json.write(Map.of("summary", "Review", "findings", findings));
    }

    private String decision(String verdict) throws Exception {
        return Json.write(
                Map.of(
                        "decisions",
                        List.of(
                                Map.of(
                                        "index",
                                        0,
                                        "verdict",
                                        verdict,
                                        "reason",
                                        "Checked against pinned code"))));
    }

    private Map<String, Object> run(CandidateVerifier model) throws Exception {
        var report = new ReviewEngine(8192, 4, "calibrated", 4096).review(source(), model);
        model.annotate(report);
        return report;
    }

    @Test
    void agreementPreservesFindingAndIncludesBothCallsInUsage() throws Exception {
        var report =
                run(
                        new CandidateVerifier(
                                model(primary(List.of(finding))),
                                model(decision("supported")),
                                3096));
        assertEquals("complete", report.get("status"));
        assertEquals(1, Json.list(report.get("findings")).size());
        assertEquals(20L, Json.map(report.get("usage")).get("promptTokens"));
    }

    @Test
    void disagreementIsVisibleAndCannotBecomeACleanZeroFindingReview() throws Exception {
        for (String verdict : List.of("disputed", "uncertain")) {
            var report =
                    run(
                            new CandidateVerifier(
                                    model(primary(List.of(finding))),
                                    model(decision(verdict)),
                                    3096));
            assertEquals("partial", report.get("status"));
            assertEquals(0, Json.list(report.get("findings")).size());
            var batch =
                    Json.map(
                            Json.list(Json.map(report.get("verification")).get("batches"))
                                    .getFirst());
            assertEquals(1, Json.list(batch.get("primaryFindings")).size());
            assertEquals("disagreement", batch.get("status"));
        }
    }

    @Test
    void malformedMissingOrExtraDecisionsCannotApproveAReview() throws Exception {
        for (String result :
                List.of(
                        "{}",
                        "{\"decisions\":[]}",
                        "{\"decisions\":[{\"index\":0,\"verdict\":\"supported\",\"reason\":\"ok\",\"extra\":true}]}")) {
            var report =
                    run(
                            new CandidateVerifier(
                                    model(primary(List.of(finding))), model(result), 3096));
            assertEquals("partial", report.get("status"));
            assertEquals(1, Json.list(report.get("findings")).size());
        }
    }

    @Test
    void verifierHasNoNewFindingChannelAndGetsTheSameImmutableChange() throws Exception {
        var seen = new AtomicInteger();
        var verifier =
                new ReviewModel() {
                    @Override
                    public String name() {
                        return "verifier";
                    }

                    @Override
                    public ModelGeneration generate(
                            String system, String input, Map<String, Object> schema)
                            throws Exception {
                        var payload = Json.object(input);
                        var change = Json.map(payload.get("change"));
                        assertEquals("b".repeat(40), change.get("headSha"));
                        assertEquals(
                                PATCH,
                                Json.map(Json.list(change.get("files")).getFirst()).get("patch"));
                        assertEquals(CandidateVerifier.SCHEMA, schema);
                        seen.incrementAndGet();
                        return new ModelGeneration(decision("supported"), 1, 10, 5);
                    }
                };
        assertEquals(
                "complete",
                run(new CandidateVerifier(model(primary(List.of(finding))), verifier, 3096))
                        .get("status"));
        assertEquals(1, seen.get());
    }

    @Test
    void noCandidatesSkipsTheVerifierAndOversizedVerificationStaysVisible() throws Exception {
        var forbidden =
                new ReviewModel() {
                    @Override
                    public String name() {
                        return "must-not-run";
                    }

                    @Override
                    public ModelGeneration generate(
                            String system, String input, Map<String, Object> schema) {
                        fail("Verifier must not be called");
                        return null;
                    }
                };
        assertEquals(
                "complete",
                run(new CandidateVerifier(model(primary(List.of())), forbidden, 3096))
                        .get("status"));
        assertEquals(
                "partial",
                run(new CandidateVerifier(model(primary(List.of(finding))), forbidden, 10))
                        .get("status"));
    }

    @Test
    void externalPolicyIsCheckedBeforeAnyProviderRequest() {
        assertThrows(
                SecurityException.class,
                () -> ReviewService.authorizeExternal(Map.of(), "github", "owner/public"));
        var policy =
                Map.of(
                        "EXTERNAL_INFERENCE_ENABLED",
                        "true",
                        "EXTERNAL_ALLOWED_REPOSITORIES",
                        "github:owner/public");
        assertDoesNotThrow(() -> ReviewService.authorizeExternal(policy, "github", "owner/public"));
        assertThrows(
                SecurityException.class,
                () -> ReviewService.authorizeExternal(policy, "github", "owner/private"));
        assertThrows(
                SecurityException.class,
                () -> ReviewService.authorizeExternal(policy, "gitlab", "owner/public"));
        assertThrows(
                SecurityException.class,
                () ->
                        new ReviewService(
                                Map.of("MODEL_PROVIDER", "openai"), "github", "owner/public"));
        assertThrows(
                SecurityException.class,
                () ->
                        new ReviewService(
                                Map.of("REVIEW_MODE", "verified", "VERIFIER_PROVIDER", "openai"),
                                "github",
                                "owner/public"));
    }
}
