package com.prreview.platform.bitbucket.service;

import com.prreview.common.json.Json;
import com.prreview.platform.bitbucket.client.BitbucketApiClient;
import com.prreview.platform.bitbucket.client.BitbucketChangeSource;
import com.prreview.platform.github.service.GitHubReviewPublisher;

import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One owned top-level summary per PR, outside MCP; ambiguous writes are reconciled, never retried.
 */
public final class BitbucketReviewPublisher {
    private final BitbucketApiClient access;

    public BitbucketReviewPublisher(BitbucketApiClient access) {
        this.access = access;
    }

    @FunctionalInterface
    public interface BeforeWrite {
        void run() throws Exception;
    }

    private record Comment(long id, String body) {}

    public static String marker(String repository, int number) {
        BitbucketApiClient.validateRepository(repository);
        return "<!-- mcp-review-poc:bitbucket:" + repository + ":" + number + " -->";
    }

    public long publish(
            String repository,
            int number,
            Map<String, Object> report,
            boolean recovering,
            BeforeWrite beforeWrite)
            throws Exception {
        BitbucketApiClient.validateRepository(repository);
        if (!"bitbucket".equals(report.get("platform"))
                || !repository.equals(report.get("repository"))
                || !(report.get("number") instanceof Number value)
                || value.longValue() != number
                || !Boolean.TRUE.equals(report.get("current"))
                || !Set.of("complete", "partial").contains(report.get("status")))
            throw new IllegalArgumentException(
                    "Report is not a current Bitbucket review for this PR");
        var source = new BitbucketChangeSource(access, repository, number);
        ensureCurrent(source.metadata(), report);
        String actor = Json.text(Json.map(access.get("user")), "uuid");
        String comments = access.repositoryPath() + "/pullrequests/" + number + "/comments";
        String marker = marker(repository, number);
        Comment existing = find(comments, marker, actor);
        if (existing == null && recovering) throw new GitHubReviewPublisher.Uncertain();
        String body =
                normalize(
                        GitHubReviewPublisher.summary(
                                Json.text(report, "headSha"), report, marker));
        ensureCurrent(source.metadata(), report);
        beforeWrite.run();
        long id;
        try {
            var response =
                    Json.map(
                            access.request(
                                    existing == null ? "POST" : "PUT",
                                    comments + (existing == null ? "" : "/" + existing.id()),
                                    Map.of("content", Map.of("raw", body))));
            id = BitbucketChangeSource.id(response);
        } catch (Exception error) {
            if (error instanceof BitbucketApiClient.RequestException response
                    && response.status() >= 400
                    && response.status() < 500) throw error;
            try {
                Comment reconciled = find(comments, marker, actor);
                if (reconciled == null || !body.equals(normalize(reconciled.body())))
                    throw new GitHubReviewPublisher.Uncertain();
                id = reconciled.id();
            } catch (Exception reconciliationFailed) {
                throw new GitHubReviewPublisher.Uncertain();
            }
        }
        try {
            ensureCurrent(source.metadata(), report);
        } catch (GitHubReviewPublisher.Outdated changed) {
            try {
                access.request(
                        "PUT",
                        comments + "/" + id,
                        Map.of(
                                "content",
                                Map.of(
                                        "raw",
                                        marker
                                                + "\n**Outdated POC review** for commit `"
                                                + Json.text(report, "headSha")
                                                + "`. The PR changed during publication.")));
            } catch (Exception updateFailed) {
                throw new GitHubReviewPublisher.Uncertain();
            }
            throw changed;
        } catch (Exception revalidationFailed) {
            throw new GitHubReviewPublisher.Uncertain();
        }
        return id;
    }

    private static void ensureCurrent(Map<String, Object> metadata, Map<String, Object> report)
            throws GitHubReviewPublisher.Outdated {
        if (!"OPEN".equals(metadata.get("state"))
                || Boolean.TRUE.equals(metadata.get("draft"))
                || !Objects.equals(
                        BitbucketChangeSource.sha(metadata, "source"), report.get("headSha"))
                || !Objects.equals(
                        BitbucketChangeSource.sha(metadata, "destination"), report.get("baseSha"))
                || !Objects.equals(
                        BitbucketChangeSource.branch(metadata), report.get("baseBranch")))
            throw new GitHubReviewPublisher.Outdated();
    }

    private Comment find(String path, String marker, String actor) throws Exception {
        String next = path + "?pagelen=100&page=1";
        var pages = new HashSet<String>();
        var seen = new HashSet<Long>();
        Comment found = null;
        for (int page = 0; page < 30; page++) {
            if (!pages.add(next)) throw new IOException("Repeated Bitbucket comments page");
            var response = Json.map(access.get(next));
            for (Object entry : Json.list(response.get("values"))) {
                var comment = Json.map(entry);
                long id = BitbucketChangeSource.id(comment);
                if (!seen.add(id))
                    throw new IOException("Repeated Bitbucket comment during pagination");
                if (Boolean.TRUE.equals(comment.get("deleted"))
                        || comment.get("parent") != null
                        || comment.get("inline") != null) continue;
                String body = Json.text(Json.map(comment.get("content")), "raw");
                if (actor.equals(Json.map(comment.get("user")).get("uuid"))
                        && body.startsWith(marker + "\n")) {
                    if (found != null)
                        throw new IOException(
                                "Multiple owned summaries; reconcile before publication");
                    found = new Comment(id, body);
                }
            }
            if (!(response.get("next") instanceof String href) || href.isBlank()) return found;
            next = access.pagePath(href, path);
        }
        throw new IOException("Comment enumeration limit reached; publication was not attempted");
    }

    private static String normalize(String text) {
        return text.replace("\r", "").stripTrailing();
    }
}
