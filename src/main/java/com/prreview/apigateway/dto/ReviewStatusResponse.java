package com.prreview.apigateway.dto;

import java.time.Instant;
import java.util.Map;

/**
 * Authenticated job status. Publication is part of normal completion, not an optional follow-up.
 */
public record ReviewStatusResponse(
        String jobId,
        String platform,
        String repository,
        int number,
        String state,
        int attempts,
        String failureCode,
        String publicationState,
        Long commentId,
        String commentUrl,
        Instant createdAt,
        Instant updatedAt,
        Map<String, Object> report) {}
