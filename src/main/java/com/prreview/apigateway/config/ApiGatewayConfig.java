package com.prreview.apigateway.config;

import java.util.List;

public record ApiGatewayConfig(
        List<AllowedRepository> repositories,
        List<String> trustedAssociations,
        boolean allowForks) {
    public ApiGatewayConfig {
        repositories = List.copyOf(repositories);
        trustedAssociations =
                trustedAssociations == null
                        ? List.of("OWNER", "MEMBER", "COLLABORATOR")
                        : List.copyOf(trustedAssociations);
    }

    public ApiGatewayConfig(List<AllowedRepository> repositories) {
        this(repositories, null, false);
    }

    public AllowedRepository target(String platform, String repository) {
        return repositories.stream()
                .filter(
                        target ->
                                target.platform().equals(platform)
                                        && target.name().equals(repository))
                .findFirst()
                .orElseThrow(
                        () -> new SecurityException("Repository is not configured for review"));
    }
}
