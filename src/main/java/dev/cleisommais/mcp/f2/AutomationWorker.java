package dev.cleisommais.mcp.f2;

import dev.cleisommais.mcp.f4.*;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Worker has no public endpoint: it claims PostgreSQL jobs and authenticates to MCP Gateway. */
public final class AutomationWorker {
    private AutomationWorker() {}
    public static boolean once(JobStore store, boolean publish) throws Exception {
        store.recoverExpired();
        String owner = UUID.randomUUID().toString();
        var job = store.claim(owner, 90); if (job == null) return false;
        var heartbeat = Executors.newSingleThreadScheduledExecutor();
        var ownsLease = new java.util.concurrent.atomic.AtomicBoolean(true);
        var wroteThisAttempt = new java.util.concurrent.atomic.AtomicBoolean(false);
        String stage = "snapshot";
        heartbeat.scheduleAtFixedRate(() -> {
            try { if (!store.heartbeat(job.id(), owner, 90)) ownsLease.set(false); }
            catch (Exception error) { ownsLease.set(false); }
        }, 15, 15, TimeUnit.SECONDS);
        try {
            if (job.installationId() != Long.parseLong(env("GITHUB_INSTALLATION_ID", "0"))) throw new SecurityException("Job installation mismatch");
            Map<String, Object> report;
            if (job.report() != null) {
                report = Json.object(job.report());
                try (var source = new GatewayReviewSource(URI.create(env("MCP_GATEWAY_URL", "http://gateway:8080")), System.getenv("GATEWAY_TOKEN"), job.repository(), job.number(), job.id())) {
                    var snapshot = source.snapshot();
                    if (!job.headSha().equals(snapshot.headSha()) || !job.baseSha().equals(snapshot.baseSha()) || snapshot.draft()
                            || !"open".equals(snapshot.state()) || !job.baseBranch().equals(snapshot.baseBranch()) || !source.isCurrent(snapshot)) throw new ReviewPublisher.Outdated();
                    report.put("revalidation", source.trace());
                }
            }
            else {
                var model = new OllamaModel(URI.create(env("OLLAMA_BASE_URL", "http://ollama:11434")), env("OLLAMA_MODEL", "gpt-oss:20b"),
                        Integer.parseInt(env("OLLAMA_CONTEXT_LENGTH", "8192")), Duration.ofSeconds(Integer.parseInt(env("REVIEW_TIMEOUT_SECONDS", "180"))),
                        OllamaModel.parseThinking(env("OLLAMA_THINK", "medium")), Integer.parseInt(env("REVIEW_OUTPUT_TOKENS", "4096")));
                stage = "model-availability";
                var metadata = model.metadata();
                stage = "snapshot";
                // F4 requires the gateway even if the historical manual F2 mode is configured elsewhere.
                try (var source = new GatewayReviewSource(URI.create(env("MCP_GATEWAY_URL", "http://gateway:8080")), System.getenv("GATEWAY_TOKEN"), job.repository(), job.number(), job.id())) {
                    var snapshot = source.snapshot();
                    if (!job.headSha().equals(snapshot.headSha()) || !job.baseSha().equals(snapshot.baseSha()) || snapshot.draft()
                            || !"open".equals(snapshot.state()) || !job.baseBranch().equals(snapshot.baseBranch())) throw new ReviewPublisher.Outdated();
                    ReviewData.Source pinned = new ReviewData.Source() {
                        @Override public ReviewData.Snapshot snapshot() { return snapshot; }
                        @Override public String fileAtCommit(String sha, String path) throws Exception { return source.fileAtCommit(sha, path); }
                        @Override public boolean isCurrent(ReviewData.Snapshot snap) throws Exception { return source.isCurrent(snap); }
                        @Override public void close() {}
                    };
                    stage = "analysis";
                    report = new ReviewEngine(Integer.parseInt(env("OLLAMA_CONTEXT_LENGTH", "8192")), Integer.parseInt(env("REVIEW_MAX_BATCHES", "4")),
                            env("REVIEW_PROMPT_PROFILE", "calibrated"), Integer.parseInt(env("REVIEW_OUTPUT_TOKENS", "4096"))).review(pinned, model);
                    report.put("inference", metadata); report.put("retrieval", source.trace()); report.put("automationJobId", job.id().toString());
                    report.put("publicationEnabled", publish);
                }
                stage = "report-persistence";
                store.saveReport(job.id(), owner, Json.write(report));
                var directory = Path.of(env("REPORT_DIR", "/reports")); Files.createDirectories(directory);
                Files.writeString(directory.resolve("job-" + job.id() + ".json"), Json.write(report) + "\n");
            }
            if (!ownsLease.get()) throw new IllegalStateException("Job lease was lost");
            if (!job.repository().equals(report.get("repository")) || !job.headSha().equals(report.get("headSha")) || !job.baseSha().equals(report.get("baseSha"))) {
                throw new SecurityException("Saved report is outside the job snapshot");
            }
            if (!Boolean.TRUE.equals(report.get("current")) || "outdated".equals(report.get("status"))) throw new ReviewPublisher.Outdated();
            if (!publish) store.finish(job.id(), owner, "complete".equals(report.get("status")) ? "analyzed" : "partial", null, null);
            else {
                stage = "publication";
                store.publicationLock(job, () -> {
                    if (!ownsLease.get()) throw new IllegalStateException("Job lease was lost");
                    var fresh = store.find(job.id());
                    var publisher = new ReviewPublisher(GitHubAccess.environment(job.repository(), true));
                    long id = publisher.publish(job, report, "uncertain".equals(fresh.publicationState()), () -> {
                        store.beginPublication(job.id(), owner); wroteThisAttempt.set(true);
                    });
                    store.finish(job.id(), owner, "complete".equals(report.get("status")) ? "succeeded" : "partial", null, id);
                    return id;
                });
            }
            System.out.println("Job " + job.id() + " completed; publication=" + publish);
        } catch (ReviewPublisher.Outdated error) { if (ownsLease.get()) store.finish(job.id(), owner, "superseded", "snapshot-changed", null); }
        catch (ReviewPublisher.Uncertain error) { if (ownsLease.get()) store.finish(job.id(), owner, "publication_uncertain", "reconciliation-required", null); }
        catch (GitHubAccess.RequestException error) {
            if (ownsLease.get()) {
                if (wroteThisAttempt.get() && !"uncertain".equals(job.publicationState()) && error.status() >= 400 && error.status() < 500) {
                    store.clearRejectedPublication(job.id(), owner);
                }
                store.finish(job.id(), owner, "failed", "github-http-" + error.status(), null);
            }
        }
        catch (Exception error) {
            if (ownsLease.get()) store.finish(job.id(), owner, "failed", stage + ":" + error.getClass().getSimpleName(), null);
            System.err.println("Job " + job.id() + " failed at " + stage + ": " + error.getClass().getSimpleName());
        } finally { heartbeat.shutdownNow(); }
        return true;
    }
    public static void main(String[] args) throws Exception {
        var store = JobStore.environment(); boolean publish = Boolean.parseBoolean(env("PUBLISH_REVIEWS", "false"));
        if (args.length == 1 && "--once".equals(args[0])) { once(store, publish); return; }
        var stopped = new java.util.concurrent.atomic.AtomicBoolean();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> stopped.set(true)));
        while (!stopped.get()) { if (!once(store, publish)) Thread.sleep(2000); }
    }
    private static String env(String key, String fallback) { return System.getenv().getOrDefault(key, fallback); }
}
