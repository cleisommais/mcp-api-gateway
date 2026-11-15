package com.prreview.agent.service;

import com.prreview.common.concurrent.ConcurrencyGate;
import com.prreview.common.config.SecretReader;
import com.prreview.common.json.Json;
import com.prreview.domain.model.ChangeSnapshot;
import com.prreview.domain.port.ChangeSource;
import com.prreview.mcp.client.GatewayMcpClient;
import com.prreview.persistence.repository.PostgresJobRepository;
import com.prreview.platform.github.client.GitHubApiClient;
import com.prreview.platform.github.service.GitHubReviewPublisher;
import com.prreview.platform.gitlab.client.GitLabApiClient;
import com.prreview.platform.gitlab.service.GitLabReviewPublisher;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Worker has no public endpoint: it claims PostgreSQL jobs and authenticates to MCP Gateway. */
public final class ReviewWorker {
    private ReviewWorker() {}

    private record Limits(int jobs, int perRepository, int leaseSeconds) {
        static Limits environment() {
            var env = System.getenv();
            return new Limits(
                    ConcurrencyGate.setting(env, "WORKER_MAX_JOBS", 1, 1, 16),
                    ConcurrencyGate.setting(env, "WORKER_MAX_JOBS_PER_REPOSITORY", 1, 1, 16),
                    ConcurrencyGate.setting(env, "WORKER_LEASE_SECONDS", 90, 6, 600));
        }
    }

    public static boolean once(PostgresJobRepository store) throws Exception {
        return once(store, Limits.environment(), ConcurrencyGate.inference(System.getenv()));
    }

    private static boolean once(
            PostgresJobRepository store, Limits limits, ConcurrencyGate inference)
            throws Exception {
        store.recoverExpired();
        String owner = UUID.randomUUID().toString();
        var job = store.claim(owner, limits.leaseSeconds(), limits.jobs(), limits.perRepository());
        if (job == null) return false;
        System.out.println(
                "Job "
                        + job.id()
                        + " started; repository="
                        + job.repository()
                        + "; attempt="
                        + job.attempts());
        var heartbeat = Executors.newSingleThreadScheduledExecutor();
        var ownsLease = new java.util.concurrent.atomic.AtomicBoolean(true);
        var wroteThisAttempt = new java.util.concurrent.atomic.AtomicBoolean(false);
        String stage = "snapshot";
        heartbeat.scheduleAtFixedRate(
                () -> {
                    try {
                        if (!store.heartbeat(job.id(), owner, limits.leaseSeconds()))
                            ownsLease.set(false);
                    } catch (Exception error) {
                        ownsLease.set(false);
                    }
                },
                Math.min(15, limits.leaseSeconds() / 3),
                Math.min(15, limits.leaseSeconds() / 3),
                TimeUnit.SECONDS);
        try {
            if (job.platform().equals("github")
                    && job.installationId() != Long.parseLong(env("GITHUB_INSTALLATION_ID", "0")))
                throw new SecurityException("Job installation mismatch");
            Map<String, Object> report;
            if (job.report() != null) {
                report = Json.object(job.report());
                try (var source =
                        new GatewayMcpClient(
                                URI.create(env("MCP_GATEWAY_URL", "http://mcp-gateway:8080")),
                                SecretReader.required("MCP_GATEWAY_TOKEN"),
                                job.platform(),
                                job.repository(),
                                job.number(),
                                job.id())) {
                    var snapshot = source.snapshot();
                    if (!job.headSha().equals(snapshot.headSha())
                            || !job.baseSha().equals(snapshot.baseSha())
                            || snapshot.draft()
                            || !"open".equals(snapshot.state())
                            || !job.baseBranch().equals(snapshot.baseBranch())
                            || !source.isCurrent(snapshot))
                        throw new GitHubReviewPublisher.Outdated();
                    report.put("revalidation", source.trace());
                }
            } else {
                stage = "model-availability";
                // Every platform read is scoped through the MCP Gateway.
                try (var session =
                                new ReviewService(
                                        System.getenv(),
                                        job.platform(),
                                        job.repository(),
                                        null,
                                        inference);
                        var source =
                                new GatewayMcpClient(
                                        URI.create(
                                                env("MCP_GATEWAY_URL", "http://mcp-gateway:8080")),
                                        SecretReader.required("MCP_GATEWAY_TOKEN"),
                                        job.platform(),
                                        job.repository(),
                                        job.number(),
                                        job.id())) {
                    stage = "snapshot";
                    var snapshot = source.snapshot();
                    if (!job.headSha().equals(snapshot.headSha())
                            || !job.baseSha().equals(snapshot.baseSha())
                            || snapshot.draft()
                            || !"open".equals(snapshot.state())
                            || !job.baseBranch().equals(snapshot.baseBranch()))
                        throw new GitHubReviewPublisher.Outdated();
                    ChangeSource pinned =
                            new ChangeSource() {
                                @Override
                                public ChangeSnapshot snapshot() {
                                    return snapshot;
                                }

                                @Override
                                public String fileAtCommit(String sha, String path)
                                        throws Exception {
                                    return source.fileAtCommit(sha, path);
                                }

                                @Override
                                public boolean isCurrent(ChangeSnapshot snap) throws Exception {
                                    return source.isCurrent(snap);
                                }

                                @Override
                                public void close() {}
                            };
                    stage = "analysis";
                    report = session.review(pinned);
                    report.put("retrieval", source.trace());
                    report.put("automationJobId", job.id().toString());
                    report.put("publicationEnabled", true);
                }
                stage = "report-persistence";
                store.saveReport(job.id(), owner, Json.write(report));
                var directory = Path.of(env("REPORT_DIR", "/reports"));
                Files.createDirectories(directory);
                Files.writeString(
                        directory.resolve("job-" + job.id() + ".json"), Json.write(report) + "\n");
            }
            if (!ownsLease.get()) throw new IllegalStateException("Job lease was lost");
            if (!job.platform().equals(report.get("platform"))
                    || !job.repository().equals(report.get("repository"))
                    || !job.headSha().equals(report.get("headSha"))
                    || !job.baseSha().equals(report.get("baseSha"))) {
                throw new SecurityException("Saved report is outside the job snapshot");
            }
            if (!Boolean.TRUE.equals(report.get("current"))
                    || "outdated".equals(report.get("status")))
                throw new GitHubReviewPublisher.Outdated();
            stage = "publication";
            store.publicationLock(
                    job,
                    () -> {
                        if (!ownsLease.get()) throw new IllegalStateException("Job lease was lost");
                        var fresh = store.find(job.id());
                        long id;
                        if (job.platform().equals("github")) {
                            id =
                                    new GitHubReviewPublisher(
                                                    GitHubApiClient.environment(
                                                            job.repository(), true))
                                            .publish(
                                                    job,
                                                    report,
                                                    "uncertain".equals(fresh.publicationState()),
                                                    () -> {
                                                        store.beginPublication(job.id(), owner);
                                                        wroteThisAttempt.set(true);
                                                    });
                        } else {
                            id =
                                    new GitLabReviewPublisher(GitLabApiClient.environment(true))
                                            .publish(
                                                    job.repository(),
                                                    job.number(),
                                                    report,
                                                    "uncertain".equals(fresh.publicationState()),
                                                    () -> {
                                                        store.beginPublication(job.id(), owner);
                                                        wroteThisAttempt.set(true);
                                                    });
                        }
                        store.finish(
                                job.id(),
                                owner,
                                "complete".equals(report.get("status")) ? "succeeded" : "partial",
                                null,
                                id);
                        return id;
                    });
            System.out.println(
                    "ReviewWorker job="
                            + job.id()
                            + " completed; commentUrl="
                            + store.find(job.id()).publicationUrl());
        } catch (GitHubReviewPublisher.Outdated error) {
            if (ownsLease.get())
                store.finish(job.id(), owner, "superseded", "snapshot-changed", null);
        } catch (GitHubReviewPublisher.Uncertain error) {
            if (ownsLease.get())
                store.finish(
                        job.id(), owner, "publication_uncertain", "reconciliation-required", null);
        } catch (GitHubApiClient.RequestException error) {
            if (ownsLease.get()) {
                if (wroteThisAttempt.get()
                        && !"uncertain".equals(job.publicationState())
                        && error.status() >= 400
                        && error.status() < 500) {
                    store.clearRejectedPublication(job.id(), owner);
                }
                store.finish(job.id(), owner, "failed", "github-http-" + error.status(), null);
            }
        } catch (GitLabApiClient.RequestException error) {
            if (ownsLease.get()) {
                if (wroteThisAttempt.get()
                        && !"uncertain".equals(job.publicationState())
                        && error.status() >= 400
                        && error.status() < 500) {
                    store.clearRejectedPublication(job.id(), owner);
                }
                store.finish(job.id(), owner, "failed", "gitlab-http-" + error.status(), null);
            }
        } catch (Exception error) {
            if (ownsLease.get())
                store.finish(
                        job.id(),
                        owner,
                        "failed",
                        stage + ":" + error.getClass().getSimpleName(),
                        null);
            System.err.println(
                    "Job "
                            + job.id()
                            + " failed at "
                            + stage
                            + ": "
                            + error.getClass().getSimpleName());
        } finally {
            heartbeat.shutdownNow();
        }
        return true;
    }

    public static void run(String[] args) throws Exception {
        if (args.length > 1 || (args.length == 1 && !args[0].equals("--once"))) {
            throw new IllegalArgumentException(
                    "Usage: agent-worker [--once]; publication is required");
        }
        var store = PostgresJobRepository.environment();
        if (args.length == 1) {
            once(store);
            return;
        }
        Files.writeString(Path.of("/tmp/agent-worker.ready"), "ready");
        System.out.println(
                "agent-worker ready; schema="
                        + env("DATABASE_SCHEMA", "review")
                        + "; publication=required");
        var limits = Limits.environment();
        var inference = ConcurrencyGate.inference(System.getenv());
        var stopped = new java.util.concurrent.atomic.AtomicBoolean();
        var threads = Executors.newFixedThreadPool(limits.jobs());
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    stopped.set(true);
                                    threads.shutdownNow();
                                }));
        for (int i = 0; i < limits.jobs(); i++)
            threads.submit(
                    () -> {
                        while (!stopped.get() && !Thread.currentThread().isInterrupted()) {
                            try {
                                if (!once(store, limits, inference)) Thread.sleep(1000);
                            } catch (InterruptedException error) {
                                Thread.currentThread().interrupt();
                            } catch (Exception error) {
                                System.err.println(
                                        "Worker poll failed: " + error.getClass().getSimpleName());
                                try {
                                    Thread.sleep(1000);
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                        }
                    });
        threads.shutdown();
        while (!threads.awaitTermination(1, TimeUnit.DAYS)) {
            /* Keep the local worker process alive. */
        }
    }

    private static String env(String key, String fallback) {
        return System.getenv().getOrDefault(key, fallback);
    }
}
