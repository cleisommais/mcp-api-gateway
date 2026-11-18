package com.prreview.agent.service;

import com.prreview.common.json.Json;
import com.prreview.domain.model.ModelGeneration;
import com.prreview.domain.port.ReviewModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A zero-candidate review needs a second pass over the same complete pinned context. */
final class CleanReviewAuditor implements ReviewModel {
    static final String VERSION = "clean-review-audit-v1";
    private static final String INSTRUCTION =
            """

Perform an independent correctness audit before concluding this change has no findings.
Trace changed control flow with concrete inputs, including boundary and empty inputs.
For newly added files, all supplied added lines are introduced code and must be reviewed.
Check that changed conditions, indexing, arithmetic and interactions preserve valid behavior.
Report only demonstrable introduced failures; a correct change still returns no findings.
Use the original exact addedLines mapping for every finding and follow the same output schema.
""";
    private final ReviewModel delegate, auditModel;
    private final List<Map<String, Object>> batches = new ArrayList<>();

    CleanReviewAuditor(ReviewModel delegate, ReviewModel auditModel) {
        this.delegate = delegate;
        this.auditModel = auditModel;
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public ModelGeneration generate(String system, String context, Map<String, Object> schema)
            throws Exception {
        var first = delegate.generate(system, context, schema);
        var output = Json.object(first.content());
        // Malformed initial output must reach the normal validation failure, never be hidden.
        if (!(output.get("summary") instanceof String)
                || output.size() != 2
                || !(output.get("findings") instanceof List<?> candidates)
                || !candidates.isEmpty()) return first;
        var audit = new java.util.LinkedHashMap<String, Object>();
        audit.put("status", "incomplete");
        batches.add(audit);
        // Exceptions deliberately propagate: an uncompleted audit cannot produce a clean review.
        var second = auditModel.generate(system + INSTRUCTION, context, schema);
        audit.put("status", "completed");
        audit.put("findings", Json.list(Json.object(second.content()).get("findings")).size());
        return new ModelGeneration(
                second.content(),
                first.durationMillis() + second.durationMillis(),
                first.promptTokens() + second.promptTokens(),
                first.outputTokens() + second.outputTokens());
    }

    void annotate(Map<String, Object> report) {
        report.put(
                "cleanReviewAudit",
                Map.of(
                        "version",
                        VERSION,
                        "batches",
                        List.copyOf(batches),
                        "scope",
                        "Second correctness pass on zero-candidate batches; not a guarantee of no"
                            + " missed defects"));
    }
}
