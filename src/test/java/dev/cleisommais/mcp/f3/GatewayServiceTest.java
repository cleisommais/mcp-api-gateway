package dev.cleisommais.mcp.f3;

import dev.cleisommais.mcp.f2.McpReviewSource;
import dev.cleisommais.mcp.f2.ReviewData;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GatewayServiceTest {
    static final String REPOSITORY = "owner/repo";
    static final String SHA = "b".repeat(40);
    static final String ID = UUID.randomUUID().toString();
    static GatewayConfig config(int limit) {
        return new GatewayConfig("poc-worker", limit, List.of(new GatewayConfig.Registration("github", "stdio",
                "dev.cleisommais.mcp.f2.GitHubMcpServer", "0.2.0", "2025-11-25", List.of(REPOSITORY, "owner/other"))));
    }
    static Map<String, Object> scope(boolean withSha) {
        var args = new HashMap<String, Object>(Map.of("jobId", ID, "repository", REPOSITORY, "number", 1));
        if (withSha) args.put("sha", SHA);
        return args;
    }
    static ReviewData.Snapshot snapshot(String repository) {
        return new ReviewData.Snapshot("github", repository, 1, "Change", "", "a".repeat(40), SHA, repository, "open", 0, true, List.of());
    }
    static final class FakeBackend implements GatewayService.Backend {
        int reads, closes, checks;
        boolean changed;
        String returnedRepo = REPOSITORY;
        @Override public void verifyCatalog() { checks++; if (changed) throw new SecurityException("Downstream schema changed"); }
        @Override public ReviewData.Snapshot snapshot() { return GatewayServiceTest.snapshot(returnedRepo); }
        @Override public String fileAtCommit(String sha, String path) { reads++; return "public class Example {}"; }
        @Override public boolean isCurrent(ReviewData.Snapshot snapshot) { return true; }
        @Override public void close() { closes++; }
    }

    @Test void routesAllowedCallsAndCorrelatesAuditsThenClosesJob() throws Exception {
        var backend = new FakeBackend();
        var events = new ArrayList<Map<String, Object>>();
        try (var service = new GatewayService(config(8), (server, repo, number) -> backend, events::add)) {
            assertEquals(SHA, ((Map<?, ?>) service.invoke("poc-worker", "github.get_change_snapshot", scope(false))).get("headSha"));
            var args = scope(true); args.put("path", "src/Example.java");
            assertEquals(Map.of("content", "public class Example {}"), service.invoke("poc-worker", "github.get_file_at_commit", args));
            assertEquals(Map.of("current", true), service.invoke("poc-worker", "github.get_change_state", scope(true)));
            service.invoke("poc-worker", "github.close_review", scope(true));
            assertEquals(1, backend.closes);
            assertThrows(SecurityException.class, () -> service.invoke("poc-worker", "github.get_change_state", scope(true)));
        }
        assertEquals(5, events.size());
        assertTrue(events.stream().allMatch(event -> ID.equals(event.get("jobId"))));
        assertEquals("denied", events.getLast().get("decision"));
        assertEquals(1, backend.reads);
    }

    @Test void deniesUnknownIdentityRepositoryToolAndDestinationBeforeOpeningBackend() {
        var opens = new java.util.concurrent.atomic.AtomicInteger();
        try (var service = new GatewayService(config(8), (server, repo, number) -> { opens.incrementAndGet(); return new FakeBackend(); }, e -> {})) {
            assertThrows(SecurityException.class, () -> service.invoke("other-worker", "github.get_change_snapshot", scope(false)));
            assertThrows(SecurityException.class, () -> service.invoke("poc-worker", "shell.exec", scope(false)));
            var wrongRepo = scope(false); wrongRepo.put("repository", "outside/repo");
            assertThrows(SecurityException.class, () -> service.invoke("poc-worker", "github.get_change_snapshot", wrongRepo));
            var url = scope(false); url.put("serverUrl", "http://unregistered.invalid/mcp");
            assertThrows(SecurityException.class, () -> service.invoke("poc-worker", "github.get_change_snapshot", url));
            var floatNumber = scope(false); floatNumber.put("number", 1.5);
            assertThrows(SecurityException.class, () -> service.invoke("poc-worker", "github.get_change_snapshot", floatNumber));
            assertEquals(0, opens.get());
        }
    }

    @Test void deniesCrossResourceShaPathAndPrChangesWithoutReadingCode() throws Exception {
        var backend = new FakeBackend();
        try (var service = new GatewayService(config(8), (s, r, n) -> backend, e -> {})) {
            service.invoke("poc-worker", "github.get_change_snapshot", scope(false));
            var repo = scope(true); repo.put("repository", "owner/other");
            assertThrows(SecurityException.class, () -> service.invoke("poc-worker", "github.get_change_state", repo));
            var pr = scope(true); pr.put("number", 2);
            assertThrows(SecurityException.class, () -> service.invoke("poc-worker", "github.get_change_state", pr));
            var sha = scope(true); sha.put("sha", "c".repeat(40)); sha.put("path", "Example.java");
            assertThrows(SecurityException.class, () -> service.invoke("poc-worker", "github.get_file_at_commit", sha));
            for (String path : List.of("../secret", "/absolute", "a\\b", "a//b", "a/./b")) {
                var args = scope(true); args.put("path", path);
                assertThrows(SecurityException.class, () -> service.invoke("poc-worker", "github.get_file_at_commit", args));
            }
            assertEquals(0, backend.reads);
        }
    }

    @Test void refusesChangedCatalogAndClosesFailedInitialization() throws Exception {
        var backend = new FakeBackend(); backend.changed = true;
        try (var service = new GatewayService(config(8), (s, r, n) -> backend, e -> {})) {
            assertThrows(SecurityException.class, () -> service.invoke("poc-worker", "github.get_change_snapshot", scope(false)));
            assertEquals(1, backend.closes);
            backend.changed = false;
            service.invoke("poc-worker", "github.get_change_snapshot", scope(false));
            backend.changed = true;
            assertThrows(SecurityException.class, () -> service.invoke("poc-worker", "github.get_change_state", scope(true)));
            service.invoke("poc-worker", "github.close_review", scope(true));
            assertEquals(2, backend.closes);
        }
    }

    @Test void closesBackendReturningWrongResource() {
        var backend = new FakeBackend(); backend.returnedRepo = "outside/repo";
        try (var service = new GatewayService(config(8), (s, r, n) -> backend, e -> {})) {
            assertThrows(SecurityException.class, () -> service.invoke("poc-worker", "github.get_change_snapshot", scope(false)));
            assertEquals(1, backend.closes);
        }
    }

    @Test void unavailableServerIsAuditedAsFailureAndIsNotRetried() {
        var events = new ArrayList<Map<String, Object>>();
        try (var service = new GatewayService(config(8), (s, r, n) -> { throw new IOException("fixture unavailable"); }, events::add)) {
            assertThrows(IOException.class, () -> service.invoke("poc-worker", "github.get_change_snapshot", scope(false)));
            assertEquals(1, events.size());
            assertEquals("failed", events.getFirst().get("decision"));
        }
    }

    @Test void boundsOpenSubprocessesAndReusesPinnedSnapshot() throws Exception {
        var opens = new java.util.concurrent.atomic.AtomicInteger();
        try (var service = new GatewayService(config(1), (s, r, n) -> { opens.incrementAndGet(); return new FakeBackend(); }, e -> {})) {
            service.invoke("poc-worker", "github.get_change_snapshot", scope(false));
            service.invoke("poc-worker", "github.get_change_snapshot", scope(false));
            var second = scope(false); second.put("jobId", UUID.randomUUID().toString());
            assertThrows(IllegalStateException.class, () -> service.invoke("poc-worker", "github.get_change_snapshot", second));
            assertEquals(1, opens.get());
        }
    }

    @Test void validatesRealDownstreamCatalogAndDetectsSchemaChangeWithoutGitHubCalls() throws Exception {
        try (var source = new McpReviewSource(REPOSITORY, 1)) {
            GatewayCatalog.validate(source.catalog());
            var changed = new ArrayList<>(source.catalog());
            var tool = changed.getFirst();
            changed.set(0, McpSchema.Tool.builder(tool.name(), Map.of("type", "object")).description(tool.description()).build());
            assertThrows(SecurityException.class, () -> GatewayCatalog.validate(changed));
            assertEquals("2025-11-25", source.initialized().protocolVersion());
            assertEquals("github-review", source.initialized().serverInfo().name());
        }
    }

    @Test void rejectsUnregisteredProcessOrAmbiguousRouting() {
        var original = config(8).servers().getFirst();
        var invalid = new GatewayConfig.Registration("other", "stdio", "java.lang.Runtime", "0.2.0", "2025-11-25", List.of(REPOSITORY));
        assertThrows(IllegalArgumentException.class, () -> new GatewayConfig("poc-worker", 8, List.of(invalid)));
        assertThrows(IllegalArgumentException.class, () -> new GatewayConfig("poc-worker", 8, List.of(original, original)));
    }
}
