package com.prreview.tools.evaluation;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class ModelRegressionRunnerTest {
    private Map<String, Object> report(List<?> findings, String coverage) {
        return Map.of(
                "enumerationComplete",
                true,
                "errors",
                List.of(),
                "rejectedFindings",
                List.of(),
                "coverage",
                List.of(Map.of("status", coverage)),
                "findings",
                findings);
    }

    @Test
    void flagsSeverityMismatchEvenWhenLocationAndExplanationAreCorrect() {
        var finding =
                new java.util.LinkedHashMap<String, Object>(
                        Map.of(
                                "file",
                                "src/demo/Checkout.java",
                                "line",
                                10,
                                "severity",
                                "MEDIUM",
                                "explanation",
                                "For quantity 1 the result is 12 instead of 1250 cents.",
                                "evidence",
                                "return (int) (price * quantity);"));
        assertEquals(
                true,
                ModelRegressionRunner.grade(3, report(List.of(finding), "analyzed")).get("passed"));
        finding.put("severity", "HIGH");
        assertEquals(
                false,
                ModelRegressionRunner.grade(3, report(List.of(finding), "analyzed")).get("passed"));
    }

    @Test
    void incompleteAnalysisCannotPassTheCleanFixture() {
        assertEquals(
                true, ModelRegressionRunner.grade(2, report(List.of(), "analyzed")).get("passed"));
        assertEquals(
                false, ModelRegressionRunner.grade(2, report(List.of(), "pending")).get("passed"));
        var failed = new java.util.LinkedHashMap<String, Object>(report(List.of(), "analyzed"));
        failed.put("errors", List.of("model timeout"));
        assertEquals(false, ModelRegressionRunner.grade(2, failed).get("passed"));
    }
}
