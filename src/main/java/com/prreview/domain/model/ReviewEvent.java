package com.prreview.domain.model;

import java.time.Instant;

public record ReviewEvent(
        String deliveryId,
        String payloadHash,
        String event,
        String action,
        long repositoryId,
        String repository,
        int number,
        long installationId,
        String baseSha,
        String headSha,
        String baseBranch,
        Instant updatedAt,
        boolean eligible,
        String reason,
        String platform) {
    public ReviewEvent {
        if (!java.util.Set.of("github", "gitlab").contains(platform))
            throw new IllegalArgumentException("Unsupported platform");
    }

    public ReviewEvent(
            String deliveryId,
            String payloadHash,
            String event,
            String action,
            long repositoryId,
            String repository,
            int number,
            long installationId,
            String baseSha,
            String headSha,
            String baseBranch,
            Instant updatedAt,
            boolean eligible,
            String reason) {
        this(
                deliveryId,
                payloadHash,
                event,
                action,
                repositoryId,
                repository,
                number,
                installationId,
                baseSha,
                headSha,
                baseBranch,
                updatedAt,
                eligible,
                reason,
                "github");
    }
}
