package com.prreview.tools;

import com.prreview.common.json.Json;
import com.prreview.persistence.repository.PostgresJobRepository;

import java.util.Map;
import java.util.UUID;

/** Local administrative CLI; no unauthenticated HTTP job/retry endpoint is exposed. */
public final class QueueAdminCli {
    private QueueAdminCli() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2
                || !java.util.List.of("show", "retry", "reanalyze", "verify").contains(args[0]))
            throw new IllegalArgumentException("Usage: show|retry|reanalyze|verify JOB_UUID");
        var store = PostgresJobRepository.environment();
        var id = UUID.fromString(args[1]);
        if (args[0].equals("retry")) {
            System.out.println("Requeued jobs: " + store.retry(id));
        }
        if (args[0].equals("reanalyze")) {
            System.out.println("Requeued for analysis: " + store.reanalyze(id));
        }
        var job = store.find(id);
        if (job == null) throw new IllegalArgumentException("Job not found");
        if (args[0].equals("verify")) {
            verify(job);
        }
        System.out.println(
                Json.write(
                        Map.of(
                                "id",
                                job.id(),
                                "repository",
                                job.repository(),
                                "number",
                                job.number(),
                                "headSha",
                                job.headSha(),
                                "state",
                                job.state(),
                                "attempts",
                                job.attempts(),
                                "publicationState",
                                job.publicationState(),
                                "publicationId",
                                job.publicationId() == null
                                        ? ""
                                        : job.publicationId().toString())));
    }

    private static void verify(com.prreview.domain.model.ReviewJob job) throws Exception {
        if (job.publicationId() == null || job.report() == null)
            throw new IllegalStateException("No published report");
        var report = Json.object(job.report());
        Map<String, Object> remote;
        String marker;
        if (job.platform().equals("github")) {
            var api =
                    com.prreview.platform.github.client.GitHubApiClient.environment(
                            job.repository(), true);
            remote =
                    Json.map(
                            api.request(
                                    job.repository(),
                                    "GET",
                                    "repos/"
                                            + job.repository()
                                            + "/issues/comments/"
                                            + job.publicationId(),
                                    null));
            if (!api.actor().equals(Json.map(remote.get("user")).get("login")))
                throw new SecurityException("Comment author mismatch");
            marker = "<!-- mcp-review-poc:" + job.repositoryId() + ":" + job.number() + " -->";
        } else {
            var api = com.prreview.platform.gitlab.client.GitLabApiClient.environment(true);
            remote =
                    Json.map(
                            api.get(
                                    "projects/"
                                            + job.repositoryId()
                                            + "/merge_requests/"
                                            + job.number()
                                            + "/notes/"
                                            + job.publicationId()));
            if (!Json.map(api.get("user"))
                    .get("id")
                    .equals(Json.map(remote.get("author")).get("id")))
                throw new SecurityException("Comment author mismatch");
            marker =
                    "<!-- mcp-review-poc:gitlab:"
                            + job.repositoryId()
                            + ":"
                            + job.number()
                            + " -->";
        }
        String expected =
                com.prreview.platform.github.service.GitHubReviewPublisher.summary(
                                job.headSha(), report, marker)
                        .replace("\r", "")
                        .stripTrailing();
        if (!expected.equals(Json.text(remote, "body").replace("\r", "").stripTrailing()))
            throw new IllegalStateException("Published comment differs from the saved report");
        System.out.println("Verified remote comment: " + job.publicationUrl());
    }
}
