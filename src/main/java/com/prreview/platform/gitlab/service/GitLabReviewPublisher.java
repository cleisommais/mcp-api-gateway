package com.prreview.platform.gitlab.service;

import com.prreview.common.json.Json;
import com.prreview.platform.github.service.GitHubReviewPublisher;
import com.prreview.platform.gitlab.client.GitLabApiClient;
import com.prreview.platform.gitlab.client.GitLabChangeSource;

import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;

/** Deterministic top-level notes, outside MCP. A write is never blindly retried. */
public final class GitLabReviewPublisher {
    private final GitLabApiClient access;

    public GitLabReviewPublisher(GitLabApiClient access) {
        this.access = access;
    }

    private record Note(long id, String body) {}

    @FunctionalInterface
    public interface BeforeWrite {
        void run() throws Exception;
    }

    public long publish(
            String repository,
            int number,
            Map<String, Object> report,
            boolean recovering,
            BeforeWrite beforeWrite)
            throws Exception {
        GitLabApiClient.validateRepository(repository);
        if (!"gitlab".equals(report.get("platform"))
                || !repository.equals(report.get("repository"))
                || GitLabChangeSource.id(report, "number") != number
                || !Boolean.TRUE.equals(report.get("current"))
                || !java.util.Set.of("complete", "partial").contains(report.get("status"))) {
            throw new IllegalArgumentException("Report is not a current GitLab review for this MR");
        }
        try (var api = new GitLabChangeSource(access, repository, number)) {
            return publishWithSource(repository, number, report, recovering, beforeWrite, api);
        }
    }

    private long publishWithSource(
            String repository,
            int number,
            Map<String, Object> report,
            boolean recovering,
            BeforeWrite beforeWrite,
            GitLabChangeSource api)
            throws Exception {
        var before = api.metadata();
        ensureCurrent(before, report);
        long project = GitLabChangeSource.id(before, "project_id");
        long actor = GitLabChangeSource.id(Json.map(access.get("user")), "id");
        String notes = "projects/" + project + "/merge_requests/" + number + "/notes";
        String marker = "<!-- mcp-review-poc:gitlab:" + project + ":" + number + " -->";
        Note existing = find(notes, marker, actor);
        if (existing == null && recovering) throw new GitHubReviewPublisher.Uncertain();
        // GitLab removes CRs and trailing whitespace when persisting a note.
        String body =
                normalize(
                        GitHubReviewPublisher.summary(
                                Json.text(report, "headSha"), report, marker));
        // Recheck after note pagination, immediately before the external effect.
        var preWrite = api.metadata();
        ensureCurrent(preWrite, report);
        if (!Objects.equals(before.get("source_project_id"), preWrite.get("source_project_id"))
                || !GitLabChangeSource.refs(before).equals(GitLabChangeSource.refs(preWrite)))
            throw new GitHubReviewPublisher.Outdated();
        beforeWrite.run();
        long id;
        try {
            var response =
                    Json.map(
                            access.request(
                                    existing == null ? "POST" : "PUT",
                                    notes + (existing == null ? "" : "/" + existing.id()),
                                    Map.of("body", body)));
            id = GitLabChangeSource.id(response, "id");
        } catch (IOException error) {
            if (error instanceof GitLabApiClient.RequestException response
                    && response.status() >= 400
                    && response.status() < 500) throw error;
            Note reconciled = find(notes, marker, actor);
            if (reconciled == null || !body.equals(normalize(reconciled.body())))
                throw new GitHubReviewPublisher.Uncertain();
            id = reconciled.id();
        }
        try {
            var after = api.metadata();
            ensureCurrent(after, report);
            if (!GitLabChangeSource.refs(before).equals(GitLabChangeSource.refs(after))
                    || !Objects.equals(
                            before.get("source_project_id"), after.get("source_project_id")))
                throw new GitHubReviewPublisher.Outdated();
        } catch (GitHubReviewPublisher.Outdated changed) {
            access.request(
                    "PUT",
                    notes + "/" + id,
                    Map.of(
                            "body",
                            marker
                                    + "\n**Outdated POC review** for commit `"
                                    + Json.text(report, "headSha")
                                    + "`. The MR changed during publication."));
            throw changed;
        }
        return id;
    }

    private static void ensureCurrent(Map<String, Object> metadata, Map<String, Object> report)
            throws GitHubReviewPublisher.Outdated {
        var refs = GitLabChangeSource.refs(metadata);
        if (!"opened".equals(metadata.get("state"))
                || Boolean.TRUE.equals(metadata.get("draft"))
                || !Objects.equals(refs.get("base_sha"), report.get("baseSha"))
                || !Objects.equals(refs.get("head_sha"), report.get("headSha"))
                || !Objects.equals(metadata.get("target_branch"), report.get("baseBranch")))
            throw new GitHubReviewPublisher.Outdated();
    }

    private static String normalize(String body) {
        return body.replace("\r", "").stripTrailing();
    }

    private Note find(String path, String marker, long actor) throws Exception {
        var seen = new HashSet<Long>();
        Note found = null;
        for (int page = 1; page <= 30; page++) {
            var entries =
                    Json.list(
                            access.get(
                                    path
                                            + "?per_page=100&page="
                                            + page
                                            + "&order_by=created_at&sort=asc"));
            for (Object item : entries) {
                var note = Json.map(item);
                long id = GitLabChangeSource.id(note, "id");
                if (!seen.add(id))
                    throw new IOException(
                            "Repeated GitLab note page; publication was not attempted");
                if (!Boolean.TRUE.equals(note.get("system"))
                        && GitLabChangeSource.id(Json.map(note.get("author")), "id") == actor
                        && Json.text(note, "body").startsWith(marker + "\n")) {
                    if (found != null)
                        throw new IOException(
                                "Multiple owned summaries; reconcile manually before publication");
                    found = new Note(id, Json.text(note, "body"));
                }
            }
            if (entries.size() < 100) return found;
        }
        throw new IOException("Note enumeration limit reached; publication was not attempted");
    }
}
