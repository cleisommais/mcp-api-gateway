package dev.cleisommais.mcp.f4;

import dev.cleisommais.mcp.f2.Json;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Controlled writes stay outside MCP/model tools and reconcile a bot-owned marker. */
public final class ReviewPublisher {
    public static final class Outdated extends Exception {}
    public static final class Uncertain extends Exception {}
    private final GitHubAccess api;
    public ReviewPublisher(GitHubAccess api) { this.api = api; }

    public long publish(JobStore.Job job, Map<String, Object> report, boolean recovering) throws Exception {
        return publish(job, report, recovering, () -> {});
    }
    @FunctionalInterface public interface BeforeWrite { void run() throws Exception; }
    public long publish(JobStore.Job job, Map<String, Object> report, boolean recovering, BeforeWrite beforeWrite) throws Exception {
        ensureCurrent(job);
        String marker = "<!-- mcp-review-poc:" + job.repositoryId() + ":" + job.number() + " -->";
        String actor = api.actor();
        Long existing = find(job, marker, actor);
        if (existing == null && recovering) throw new Uncertain();
        String body = summary(job, report, marker);
        long id;
        beforeWrite.run();
        try {
            var response = Json.map(api.request(job.repository(), existing == null ? "POST" : "PATCH",
                    "repos/" + job.repository() + (existing == null ? "/issues/" + job.number() + "/comments" : "/issues/comments/" + existing), Map.of("body", body)));
            id = ((Number) response.get("id")).longValue();
        } catch (IOException error) {
            if (error instanceof GitHubAccess.RequestException response && response.status() >= 400 && response.status() < 500) throw error;
            // A response may be lost after GitHub committed the write. Never blindly POST again.
            Long reconciled = find(job, marker, actor);
            if (reconciled == null) throw new Uncertain();
            id = reconciled;
        }
        try { ensureCurrent(job); }
        catch (Outdated changed) {
            api.request(job.repository(), "PATCH", "repos/" + job.repository() + "/issues/comments/" + id,
                    Map.of("body", marker + "\n**Outdated POC review** for commit `" + job.headSha() + "`. The PR changed during publication."));
            throw changed;
        }
        return id;
    }
    private void ensureCurrent(JobStore.Job job) throws Exception {
        var pr = Json.map(api.request(job.repository(), "GET", "repos/" + job.repository() + "/pulls/" + job.number(), null));
        if (!"open".equals(pr.get("state")) || Boolean.TRUE.equals(pr.get("draft"))
                || !job.headSha().equals(Json.map(pr.get("head")).get("sha"))
                || !job.baseSha().equals(Json.map(pr.get("base")).get("sha"))
                || !job.baseBranch().equals(Json.map(pr.get("base")).get("ref"))) throw new Outdated();
    }
    private Long find(JobStore.Job job, String marker, String actor) throws Exception {
        for (int page = 1; page <= 30; page++) {
            var comments = Json.list(api.request(job.repository(), "GET", "repos/" + job.repository() + "/issues/" + job.number() + "/comments?per_page=100&page=" + page, null));
            for (Object item : comments) {
                var comment = Json.map(item);
                if (actor.equals(Json.map(comment.get("user")).get("login")) && Json.text(comment, "body").startsWith(marker)) {
                    return ((Number) comment.get("id")).longValue();
                }
            }
            if (comments.size() < 100) return null;
        }
        throw new IOException("Comment enumeration limit reached; publication was not attempted");
    }
    static String summary(JobStore.Job job, Map<String, Object> report, String marker) {
        return summary(job.headSha(), report, marker);
    }
    public static String summary(String headSha, Map<String, Object> report, String marker) {
        var result = new StringBuilder(marker).append("\n## Automated POC review\n\nAnalyzed commit `").append(headSha)
                .append("`. Static analysis only; human review is still required.\n\n")
                .append("Coverage status: **").append(escape(String.valueOf(report.get("status")))).append("**; files enumerated: ")
                .append(report.get("enumeratedFiles")).append(".\n\n");
        if (report.get("verification") instanceof Map<?, ?> verification) {
            result.append("Review mode: **verified**; candidate-check status: **")
                    .append(escape(String.valueOf(verification.get("status")))).append("**. ")
                    .append("This checks primary candidates only, not missed defects. Inspect the saved report for preserved primary findings and disagreements.\n\n");
        }
        var findings = Json.list(report.get("findings"));
        if (findings.isEmpty()) result.append("No validated findings were produced. This is not an approval or a guarantee that the change is correct.\n");
        for (Object item : findings) {
            var finding = Json.map(item);
            String text = "- **" + escape(Json.text(finding, "severity")) + "** " + escape(Json.text(finding, "file")) + ":" + finding.get("line")
                    + " — " + escape(Json.text(finding, "explanation")) + "\n";
            if (result.length() + text.length() > 55_000) { result.append("\nAdditional findings remain in the saved local report.\n"); break; }
            result.append(text);
        }
        long pending = Json.list(report.get("coverage")).stream().map(Json::map)
                .filter(entry -> !List.of("analyzed", "excluded").contains(entry.get("status"))).count();
        result.append("\nFiles requiring further analysis: ").append(pending).append(". Model: ").append(escape(String.valueOf(report.get("model"))))
                .append(".\n");
        return result.toString();
    }
    private static String escape(String value) {
        return value.replace('\r', ' ').replace('\n', ' ').replace("@", "@\u200b").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\\", "\\\\").replace("`", "\\`").replace("[", "\\[").replace("]", "\\]")
                .replace("(", "\\(").replace(")", "\\)").replace("*", "\\*").replace("_", "\\_").replace("!", "\\!");
    }
}
