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
    private record Limits(int jobs, int perRepository, int leaseSeconds) {
        static Limits environment() {
            var env = System.getenv();
            return new Limits(dev.cleisommais.mcp.f7.CallGate.setting(env, "WORKER_MAX_JOBS", 1, 1, 16),
                    dev.cleisommais.mcp.f7.CallGate.setting(env, "WORKER_MAX_JOBS_PER_REPOSITORY", 1, 1, 16),
                    dev.cleisommais.mcp.f7.CallGate.setting(env, "WORKER_LEASE_SECONDS", 90, 6, 600));
        }
    }
    public static boolean once(JobStore store, boolean publish) throws Exception {
        return once(store, publish, Limits.environment(), dev.cleisommais.mcp.f7.CallGate.inference(System.getenv()));
    }
    private static boolean once(JobStore store, boolean publish, Limits limits, dev.cleisommais.mcp.f7.CallGate inference) throws Exception {
        store.recoverExpired();
        String owner = UUID.randomUUID().toString();
        var job = store.claim(owner, limits.leaseSeconds(), limits.jobs(), limits.perRepository()); if (job == null) return false;
        System.out.println("Job " + job.id() + " started; repository=" + job.repository() + "; attempt=" + job.attempts());
        var heartbeat = Executors.newSingleThreadScheduledExecutor();
        var ownsLease = new java.util.concurrent.atomic.AtomicBoolean(true);
        var wroteThisAttempt = new java.util.concurrent.atomic.AtomicBoolean(false);
        String stage = "snapshot";
        heartbeat.scheduleAtFixedRate(() -> {
            try { if (!store.heartbeat(job.id(), owner, limits.leaseSeconds())) ownsLease.set(false); }
            catch (Exception error) { ownsLease.set(false); }
        }, Math.min(15, limits.leaseSeconds() / 3), Math.min(15, limits.leaseSeconds() / 3), TimeUnit.SECONDS);
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
                stage = "model-availability";
                // F4 requires the gateway even if the historical manual F2 mode is configured elsewhere.
                try (var session = new ReviewSession(System.getenv(), "github", job.repository(), null, inference);
                     var source = new GatewayReviewSource(URI.create(env("MCP_GATEWAY_URL", "http://gateway:8080")), System.getenv("GATEWAY_TOKEN"), job.repository(), job.number(), job.id())) {
                    stage = "snapshot";
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
                    report = session.review(pinned);
                    report.put("retrieval", source.trace()); report.put("automationJobId", job.id().toString());
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
        var limits = Limits.environment(); var inference = dev.cleisommais.mcp.f7.CallGate.inference(System.getenv());
        var stopped = new java.util.concurrent.atomic.AtomicBoolean();
        var threads = Executors.newFixedThreadPool(limits.jobs());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { stopped.set(true); threads.shutdownNow(); }));
        for (int i = 0; i < limits.jobs(); i++) threads.submit(() -> {
            while (!stopped.get() && !Thread.currentThread().isInterrupted()) {
                try { if (!once(store, publish, limits, inference)) Thread.sleep(1000); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                catch (Exception error) {
                    System.err.println("Worker poll failed: " + error.getClass().getSimpleName());
                    try { Thread.sleep(1000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                }
            }
        });
        threads.shutdown();
        while (!threads.awaitTermination(1, TimeUnit.DAYS)) { /* Keep the local worker process alive. */ }
    }
    private static String env(String key, String fallback) { return System.getenv().getOrDefault(key, fallback); }
}
