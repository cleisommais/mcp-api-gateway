package com.prreview.domain.model;

public record FileChange(
        String path,
        String status,
        String patch,
        boolean patchComplete,
        int additions,
        int deletions,
        String category) {}
