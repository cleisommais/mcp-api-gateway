package com.prreview.domain.model;

import java.util.List;

public record ChangeSnapshot(
        String platform,
        String repository,
        int number,
        String title,
        String description,
        String baseSha,
        String headSha,
        String headRepository,
        String state,
        int expectedFiles,
        boolean enumerationComplete,
        List<FileChange> files,
        boolean draft,
        String baseBranch) {
    public ChangeSnapshot {
        if (baseBranch == null) baseBranch = "";
    }

    public ChangeSnapshot(
            String platform,
            String repository,
            int number,
            String title,
            String description,
            String baseSha,
            String headSha,
            String headRepository,
            String state,
            int expectedFiles,
            boolean enumerationComplete,
            List<FileChange> files) {
        this(
                platform,
                repository,
                number,
                title,
                description,
                baseSha,
                headSha,
                headRepository,
                state,
                expectedFiles,
                enumerationComplete,
                files,
                false,
                "");
    }
}
