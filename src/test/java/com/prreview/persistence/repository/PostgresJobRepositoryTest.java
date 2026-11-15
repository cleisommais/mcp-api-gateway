package com.prreview.persistence.repository;

import static org.junit.jupiter.api.Assertions.*;

import com.prreview.common.config.SecretReader;
import com.prreview.domain.model.ChangeSnapshot;
import com.prreview.domain.model.ReviewEvent;
import com.prreview.domain.model.ReviewJob;
import com.prreview.domain.port.ChangeSource;
import com.prreview.support.GitHubFixture;

import org.junit.jupiter.api.*;

import java.util.UUID;

public class PostgresJobRepositoryTest {
    private PostgresJobRepository store;
    private String schema;

    @BeforeEach
    void start() throws Exception {
        Assumptions.assumeTrue(
                System.getenv("TEST_DATABASE_URL") != null,
                "Run the Compose checks service for real PostgreSQL");
        schema = "test_queue_" + UUID.randomUUID().toString().replace("-", "");
        try (var startup = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var edgeStart = startup.submit(this::fresh);
            var workerStart = startup.submit(this::fresh);
            store = edgeStart.get();
            assertNotNull(workerStart.get());
        }
    }

    private PostgresJobRepository fresh() throws Exception {
        return new PostgresJobRepository(
                System.getenv("TEST_DATABASE_URL"),
                System.getenv("TEST_DATABASE_USER"),
                SecretReader.optional("TEST_DATABASE_PASSWORD"),
                schema);
    }

    @AfterEach
    void close() throws Exception {
        if (store != null)
            try (var connection = store.connection();
                    var statement = connection.createStatement()) {
                assertTrue(schema.startsWith("test_queue_"));
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
    }

    @Test
    void reanalysisPreservesCommentIdentityAndCannotStealActiveWork() throws Exception {
        var id = store.enqueue(GitHubFixture.event()).jobId();
        assertEquals(0, store.reanalyze(id));
        store.claim("owner", 90);
        assertEquals(0, store.reanalyze(id));
        store.saveReport(id, "owner", "{}");
        store.beginPublication(id, "owner");
        store.finish(id, "owner", "succeeded", null, 77L);
        assertEquals(1, store.reanalyze(id));
        var job = store.find(id);
        assertEquals("queued", job.state());
        assertNull(job.report());
        assertEquals(77L, job.publicationId());
        assertEquals("published", job.publicationState());
    }

    @Test
    void deduplicatesAndSurvivesConnectionRestart() throws Exception {
        var event = GitHubFixture.event();
        var receipt = store.enqueue(event);
        assertFalse(receipt.duplicate());
        assertEquals(receipt.jobId(), store.enqueue(event).jobId());
        assertTrue(store.enqueue(event).duplicate());
        var redelivery =
                new ReviewEvent(
                        UUID.randomUUID().toString(),
                        event.payloadHash(),
                        event.event(),
                        event.action(),
                        event.repositoryId(),
                        event.repository(),
                        event.number(),
                        event.installationId(),
                        event.baseSha(),
                        event.headSha(),
                        event.baseBranch(),
                        event.updatedAt(),
                        true,
                        "accepted");
        assertEquals(receipt.jobId(), store.enqueue(redelivery).jobId());
        var reopened = fresh();
        assertEquals("queued", reopened.find(receipt.jobId()).state());
        var first = store.claim("worker-one", 90);
        assertNotNull(first);
        assertNull(reopened.claim("worker-two", 90));
        assertFalse(store.heartbeat(first.id(), "other-owner", 90));
        assertTrue(store.heartbeat(first.id(), "worker-one", 90));
        assertEquals(0, store.recoverExpired());
        var tampered =
                new ReviewEvent(
                        event.deliveryId(),
                        "0".repeat(64),
                        event.event(),
                        event.action(),
                        event.repositoryId(),
                        event.repository(),
                        event.number(),
                        event.installationId(),
                        event.baseSha(),
                        event.headSha(),
                        event.baseBranch(),
                        event.updatedAt(),
                        true,
                        "accepted");
        assertThrows(SecurityException.class, () -> store.enqueue(tampered));
    }

    @Test
    void recoversExpiredPublicationWithoutLosingSavedReportOrAmbiguity() throws Exception {
        var receipt = store.enqueue(GitHubFixture.event());
        store.claim("old-worker", 90);
        store.saveReport(receipt.jobId(), "old-worker", "{\"current\":true}");
        store.beginPublication(receipt.jobId(), "old-worker");
        try (var connection = store.connection();
                var statement =
                        connection.prepareStatement(
                                "UPDATE jobs SET lease_until=now()-interval '1 second' WHERE"
                                        + " id=?")) {
            statement.setObject(1, receipt.jobId());
            statement.executeUpdate();
        }
        assertFalse(
                store.heartbeat(receipt.jobId(), "old-worker", 90),
                "An expired owner cannot revive its lease");
        assertEquals(1, fresh().recoverExpired());
        var recovered = store.claim("new-worker", 90);
        assertEquals("uncertain", recovered.publicationState());
        assertTrue(recovered.report().contains("current"));
        assertEquals(2, recovered.attempts());
        assertThrows(
                IllegalStateException.class,
                () -> store.finish(receipt.jobId(), "old-worker", "failed", "lost", null));
        store.finish(receipt.jobId(), "new-worker", "succeeded", null, 77L);
        assertEquals("succeeded", store.find(receipt.jobId()).state());
        assertEquals(77L, store.find(receipt.jobId()).publicationId());
        assertEquals("published", store.find(receipt.jobId()).publicationState());
    }

    @Test
    void concurrentDeliveryCreatesOneJobAndNewerEventSupersedesQueuedOldHead() throws Exception {
        var event = GitHubFixture.event();
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> store.enqueue(event));
            var b = executor.submit(() -> store.enqueue(event));
            assertEquals(a.get().jobId(), b.get().jobId());
        }
        var oldId = store.enqueue(event).jobId();
        var next =
                new ReviewEvent(
                        UUID.randomUUID().toString(),
                        "c".repeat(64),
                        event.event(),
                        "synchronize",
                        event.repositoryId(),
                        event.repository(),
                        event.number(),
                        event.installationId(),
                        event.baseSha(),
                        "d".repeat(40),
                        event.baseBranch(),
                        event.updatedAt().plusSeconds(1),
                        true,
                        "accepted");
        var newId = store.enqueue(next).jobId();
        assertEquals("superseded", store.find(oldId).state());
        assertEquals(newId, store.claim("worker", 90).id());
    }

    private ReviewEvent event(long repository, int number) throws Exception {
        var base = GitHubFixture.event();
        return new ReviewEvent(
                UUID.randomUUID().toString(),
                "a".repeat(64),
                "pull_request",
                "opened",
                repository,
                "owner/repo" + repository,
                number,
                base.installationId(),
                base.baseSha(),
                base.headSha(),
                "main",
                base.updatedAt(),
                true,
                "accepted");
    }

    @Test
    void activeAndRepositoryLimitsAreIndependentAndConfigurable() throws Exception {
        store.enqueue(event(1, 1));
        store.enqueue(event(1, 2));
        store.enqueue(event(2, 1));
        var first = store.claim("one", 90, 1, 1);
        assertNotNull(first);
        assertNull(store.claim("two", 90, 1, 1));
        var second = store.claim("two", 90, 2, 1);
        assertNotNull(second);
        assertNotEquals(first.repositoryId(), second.repositoryId());
        assertNull(store.claim("three", 90, 2, 1));
        store.finish(first.id(), "one", "succeeded", null, 99L);
        var third = store.claim("three", 90, 2, 1);
        assertNotNull(third);
        assertEquals(first.repositoryId(), third.repositoryId());
    }

    @Test
    void competingClaimsCannotOversubscribeGlobalOrRepositoryLimits() throws Exception {
        for (int number = 1; number <= 5; number++) {
            store.enqueue(event(1, number));
            store.enqueue(event(2, number));
        }
        try (var threads = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<ReviewJob>>();
            for (int i = 0; i < 8; i++) {
                String owner = "owner-" + i;
                futures.add(threads.submit(() -> fresh().claim(owner, 90, 2, 1)));
            }
            var claimed = new java.util.ArrayList<ReviewJob>();
            for (var future : futures) {
                var job = future.get();
                if (job != null) claimed.add(job);
            }
            assertEquals(2, claimed.size());
            assertNotEquals(claimed.get(0).repositoryId(), claimed.get(1).repositoryId());
        }
    }

    @Test
    void manualHttpContractPinsQueuesDeduplicatesAndReturnsPublishedStatus() throws Exception {
        var config =
                new com.prreview.apigateway.config.ApiGatewayConfig(
                        java.util.List.of(
                                new com.prreview.apigateway.config.AllowedRepository(
                                        17, "owner/private-repo", "main")));
        var service =
                new com.prreview.apigateway.service.ReviewRequestService(
                        store,
                        config,
                        (platform, repository, number, id) ->
                                new ChangeSource() {
                                    public ChangeSnapshot snapshot() {
                                        return new ChangeSnapshot(
                                                platform,
                                                repository,
                                                number,
                                                "title",
                                                "body",
                                                "a".repeat(40),
                                                "b".repeat(40),
                                                repository,
                                                "open",
                                                0,
                                                true,
                                                java.util.List.of(),
                                                false,
                                                "main");
                                    }

                                    public String fileAtCommit(String sha, String path) {
                                        throw new AssertionError(
                                                "Metadata admission must not fetch file contents");
                                    }

                                    public boolean isCurrent(ChangeSnapshot snapshot) {
                                        return true;
                                    }

                                    public void close() {}
                                },
                        42);
        String token = "t".repeat(40);
        var policy =
                new com.prreview.apigateway.security.GitHubWebhookPolicy(
                        "s".repeat(40), 42, config);
        try (var api =
                new com.prreview.apigateway.ApiGatewayApplication(
                        0, policy, store::enqueue, service, token)) {
            var http = java.net.http.HttpClient.newHttpClient();
            var uri = java.net.URI.create("http://localhost:" + api.port() + "/api/reviews");
            var body =
                    "{\"platform\":\"github\",\"repository\":\"owner/private-repo\",\"number\":1}";
            assertEquals(
                    401,
                    http.send(
                                    java.net.http.HttpRequest.newBuilder(uri)
                                            .POST(
                                                    java.net.http.HttpRequest.BodyPublishers
                                                            .ofString(body))
                                            .build(),
                                    java.net.http.HttpResponse.BodyHandlers.discarding())
                            .statusCode());
            var request =
                    java.net.http.HttpRequest.newBuilder(uri)
                            .header("Authorization", "Bearer " + token)
                            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                            .build();
            var response = http.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(202, response.statusCode(), response.body());
            var created = com.prreview.common.json.Json.object(response.body());
            assertEquals(
                    200,
                    http.send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
                            .statusCode());
            var job = store.claim("http-worker", 90);
            store.saveReport(job.id(), "http-worker", "{\"status\":\"complete\"}");
            store.beginPublication(job.id(), "http-worker");
            store.finish(job.id(), "http-worker", "succeeded", null, 123L);
            var status =
                    http.send(
                            java.net.http.HttpRequest.newBuilder(
                                            java.net.URI.create(uri + "/" + created.get("jobId")))
                                    .header("Authorization", "Bearer " + token)
                                    .GET()
                                    .build(),
                            java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(200, status.statusCode(), status.body());
            assertTrue(status.body().contains("issuecomment-123"));
            var denied =
                    java.net.http.HttpRequest.newBuilder(uri)
                            .header("Authorization", "Bearer " + token)
                            .POST(
                                    java.net.http.HttpRequest.BodyPublishers.ofString(
                                            body.replace("owner/private-repo", "other/repo")))
                            .build();
            assertEquals(
                    403,
                    http.send(denied, java.net.http.HttpResponse.BodyHandlers.discarding())
                            .statusCode());
            var malformed =
                    java.net.http.HttpRequest.newBuilder(uri)
                            .header("Authorization", "Bearer " + token)
                            .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{} imprecise"))
                            .build();
            assertEquals(
                    400,
                    http.send(malformed, java.net.http.HttpResponse.BodyHandlers.discarding())
                            .statusCode());
        }
    }

    @Test
    void platformsDoNotCollideWhenNumericRepositoryIdsMatch() throws Exception {
        var github = event(1, 1);
        var gitlab =
                new ReviewEvent(
                        UUID.randomUUID().toString(),
                        github.payloadHash(),
                        github.event(),
                        github.action(),
                        github.repositoryId(),
                        github.repository(),
                        github.number(),
                        0,
                        github.baseSha(),
                        github.headSha(),
                        github.baseBranch(),
                        github.updatedAt(),
                        true,
                        "accepted",
                        "gitlab");
        assertNotEquals(store.enqueue(github).jobId(), store.enqueue(gitlab).jobId());
        var first = store.claim("one", 90, 2, 1);
        var second = store.claim("two", 90, 2, 1);
        assertNotNull(first);
        assertNotNull(second);
        assertNotEquals(first.platform(), second.platform());
    }
}
