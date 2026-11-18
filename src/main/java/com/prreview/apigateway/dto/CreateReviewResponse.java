package com.prreview.apigateway.dto;

/** HTTP 202, or 200 when this exact snapshot already has a job. */
public record CreateReviewResponse(
        String jobId, String state, boolean duplicate, String statusUrl) {}
