package dev.cleisommais.mcp.f4;

import org.junit.jupiter.api.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class JobStoreIntegrationTest {
    private JobStore store;
    private String schema;
    @BeforeEach void start() throws Exception {
        Assumptions.assumeTrue(System.getenv("TEST_DATABASE_URL") != null, "Run the Compose checks service for real PostgreSQL");
        schema = "f4_it_" + UUID.randomUUID().toString().replace("-", "");
        try (var startup = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var edgeStart = startup.submit(this::fresh); var workerStart = startup.submit(this::fresh);
            store = edgeStart.get(); assertNotNull(workerStart.get());
        }
    }
    private JobStore fresh() throws Exception { return new JobStore(System.getenv("TEST_DATABASE_URL"),
            System.getenv("TEST_DATABASE_USER"), System.getenv("TEST_DATABASE_PASSWORD"), schema); }
    @AfterEach void close() throws Exception {
        if (store != null) try (var connection = store.connection(); var statement = connection.createStatement()) {
            assertTrue(schema.startsWith("f4_it_")); statement.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }
    @Test void deduplicatesAndSurvivesConnectionRestart() throws Exception {
        var event = WebhookPolicyTest.event(); var receipt = store.enqueue(event);
        assertFalse(receipt.duplicate()); assertEquals(receipt.jobId(), store.enqueue(event).jobId());
        assertTrue(store.enqueue(event).duplicate());
        var redelivery = new WebhookPolicy.Event(UUID.randomUUID().toString(), event.payloadHash(), event.event(), event.action(), event.repositoryId(), event.repository(),
                event.number(), event.installationId(), event.baseSha(), event.headSha(), event.baseBranch(), event.updatedAt(), true, "accepted");
        assertEquals(receipt.jobId(), store.enqueue(redelivery).jobId());
        var reopened = fresh(); assertEquals("queued", reopened.find(receipt.jobId()).state());
        var first = store.claim("worker-one", 90); assertNotNull(first); assertNull(reopened.claim("worker-two", 90));
        assertFalse(store.heartbeat(first.id(), "other-owner", 90)); assertTrue(store.heartbeat(first.id(), "worker-one", 90));
        assertEquals(0, store.recoverExpired());
        var tampered = new WebhookPolicy.Event(event.deliveryId(), "0".repeat(64), event.event(), event.action(), event.repositoryId(), event.repository(),
                event.number(), event.installationId(), event.baseSha(), event.headSha(), event.baseBranch(), event.updatedAt(), true, "accepted");
        assertThrows(SecurityException.class, () -> store.enqueue(tampered));
    }
    @Test void recoversExpiredPublicationWithoutLosingSavedReportOrAmbiguity() throws Exception {
        var receipt = store.enqueue(WebhookPolicyTest.event()); store.claim("old-worker", 90);
        store.saveReport(receipt.jobId(), "old-worker", "{\"current\":true}"); store.beginPublication(receipt.jobId(), "old-worker");
        try (var connection = store.connection(); var statement = connection.prepareStatement("UPDATE jobs SET lease_until=now()-interval '1 second' WHERE id=?")) {
            statement.setObject(1, receipt.jobId()); statement.executeUpdate();
        }
        assertEquals(1, fresh().recoverExpired()); var recovered = store.claim("new-worker", 90);
        assertEquals("uncertain", recovered.publicationState()); assertTrue(recovered.report().contains("current")); assertEquals(2, recovered.attempts());
        assertThrows(IllegalStateException.class, () -> store.finish(receipt.jobId(), "old-worker", "failed", "lost", null));
        store.finish(receipt.jobId(), "new-worker", "succeeded", null, 77L);
        assertEquals("succeeded", store.find(receipt.jobId()).state());
        assertEquals(77L, store.find(receipt.jobId()).publicationId());
        assertEquals("published", store.find(receipt.jobId()).publicationState());
    }
    @Test void concurrentDeliveryCreatesOneJobAndNewerEventSupersedesQueuedOldHead() throws Exception {
        var event = WebhookPolicyTest.event();
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> store.enqueue(event)); var b = executor.submit(() -> store.enqueue(event));
            assertEquals(a.get().jobId(), b.get().jobId());
        }
        var oldId = store.enqueue(event).jobId();
        var next = new WebhookPolicy.Event(UUID.randomUUID().toString(), "c".repeat(64), event.event(), "synchronize", event.repositoryId(), event.repository(),
                event.number(), event.installationId(), event.baseSha(), "d".repeat(40), event.baseBranch(), event.updatedAt().plusSeconds(1), true, "accepted");
        var newId = store.enqueue(next).jobId();
        assertEquals("superseded", store.find(oldId).state()); assertEquals(newId, store.claim("worker", 90).id());
    }
}
