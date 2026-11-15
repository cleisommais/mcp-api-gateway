package com.prreview.domain.model;

import java.time.Instant;
import java.util.UUID;

/** Immutable database model of a review at a fixed repository/base/head snapshot. */
public record ReviewJob(
        UUID id,
        String platform,
        long repositoryId,
        String repository,
        int number,
        long installationId,
        String baseSha,
        String headSha,
        String baseBranch,
        Instant sourceUpdatedAt,
        String state,
        int attempts,
        String runOwner,
        Instant leaseUntil,
        String report,
        String failureCode,
        String publicationState,
        Long publicationId,
        Instant createdAt,
        Instant updatedAt) {
    public ReviewJob(
            UUID id,
            long repositoryId,
            String repository,
            int number,
            long installationId,
            String baseSha,
            String headSha,
            String baseBranch,
            String state,
            int attempts,
            String report,
            String publicationState,
            Long publicationId) {
        this(
                id,
                "github",
                repositoryId,
                repository,
                number,
                installationId,
                baseSha,
                headSha,
                baseBranch,
                null,
                state,
                attempts,
                null,
                null,
                report,
                null,
                publicationState,
                publicationId,
                null,
                null);
    }

    public String publicationUrl() {
        if (publicationId == null) return null;
        return platform.equals("github")
                ? "https://github.com/"
                        + repository
                        + "/pull/"
                        + number
                        + "#issuecomment-"
                        + publicationId
                : "https://gitlab.com/"
                        + repository
                        + "/-/merge_requests/"
                        + number
                        + "#note_"
                        + publicationId;
    }
}
