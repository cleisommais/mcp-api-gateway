package com.prreview.domain.model;

public record ModelGeneration(
        String content, long durationMillis, long promptTokens, long outputTokens) {}
