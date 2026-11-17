package com.prreview.apigateway.dto;

import com.prreview.platform.gitlab.client.GitLabApiClient;

/** POST /api/reviews. The server, not the caller/model, chooses credentials and destinations. */
public record CreateReviewRequest(String platform, String repository, int number) {
    public CreateReviewRequest {
        if (!java.util.Set.of("github", "gitlab", "bitbucket").contains(platform)
                || repository == null
                || number < 1) {
            throw new IllegalArgumentException(
                    "Expected platform, repository and positive PR/MR number");
        }
        GitLabApiClient.validateRepository(repository);
        if (!platform.equals("gitlab") && repository.split("/").length != 2)
            throw new IllegalArgumentException("Expected owner/repository");
    }
}
