package com.prreview.apigateway.config;

/** Repository allowlist. Legacy baseBranch configuration is accepted but never filters PRs. */
public record AllowedRepository(long id, String name, String baseBranch, String platform) {
    public AllowedRepository {
        if (platform == null) platform = "github";
    }

    public AllowedRepository(long id, String name, String baseBranch) {
        this(id, name, baseBranch, "github");
    }
}
