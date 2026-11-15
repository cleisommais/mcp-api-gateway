package com.prreview.apigateway.service;

import com.prreview.apigateway.config.ApiGatewayConfig;
import com.prreview.apigateway.dto.CreateReviewRequest;
import com.prreview.apigateway.dto.CreateReviewResponse;
import com.prreview.apigateway.dto.ReviewStatusResponse;
import com.prreview.common.json.Json;
import com.prreview.domain.model.ReviewEvent;
import com.prreview.domain.port.ChangeSource;
import com.prreview.persistence.repository.PostgresJobRepository;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/** Validates an allowed manual target, pins its snapshot through MCP, then persists the job. */
public final class ReviewRequestService {
    @FunctionalInterface
    public interface SourceFactory {
        ChangeSource open(String platform, String repository, int number, UUID requestId)
                throws Exception;
    }

    private final PostgresJobRepository jobs;
    private final ApiGatewayConfig policy;
    private final SourceFactory sources;
    private final long installationId;

    public ReviewRequestService(
            PostgresJobRepository jobs,
            ApiGatewayConfig policy,
            SourceFactory sources,
            long installationId) {
        this.jobs = jobs;
        this.policy = policy;
        this.sources = sources;
        this.installationId = installationId;
    }

    public CreateReviewResponse create(CreateReviewRequest request) throws Exception {
        var target = policy.target(request.platform(), request.repository());
        UUID requestId = UUID.randomUUID();
        com.prreview.domain.model.ChangeSnapshot snapshot;
        try (var source =
                sources.open(
                        request.platform(), request.repository(), request.number(), requestId)) {
            snapshot = source.snapshot();
            if (!snapshot.platform().equals(request.platform())
                    || !snapshot.repository().equals(request.repository())
                    || snapshot.number() != request.number()) {
                throw new SecurityException("Snapshot is outside the requested resource");
            }
            if (!snapshot.state().equals("open")
                    || snapshot.draft()
                    || !target.baseBranch().equals(snapshot.baseBranch())
                    || !source.isCurrent(snapshot)) {
                throw new IllegalArgumentException(
                        "Review requires a current open non-draft change on its configured target"
                                + " branch");
            }
        } // Close admission MCP context before the worker can claim this ID.
        String hash =
                HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(
                                                Json.write(request)
                                                        .getBytes(
                                                                java.nio.charset.StandardCharsets
                                                                        .UTF_8)));
        var receipt =
                jobs.enqueue(
                        new ReviewEvent(
                                requestId.toString(),
                                hash,
                                "manual_review",
                                "requested",
                                target.id(),
                                request.repository(),
                                request.number(),
                                request.platform().equals("github") ? installationId : 0,
                                snapshot.baseSha(),
                                snapshot.headSha(),
                                snapshot.baseBranch(),
                                Instant.now(),
                                true,
                                "accepted",
                                request.platform()));
        var job = jobs.find(receipt.jobId());
        return new CreateReviewResponse(
                job.id().toString(), job.state(), receipt.duplicate(), "/api/reviews/" + job.id());
    }

    public ReviewStatusResponse status(UUID id) throws Exception {
        var job = jobs.find(id);
        if (job == null) return null;
        return new ReviewStatusResponse(
                job.id().toString(),
                job.platform(),
                job.repository(),
                job.number(),
                job.state(),
                job.attempts(),
                job.failureCode(),
                job.publicationState(),
                job.publicationId(),
                job.publicationUrl(),
                job.createdAt(),
                job.updatedAt(),
                job.report() == null ? null : Json.object(job.report()));
    }
}
