package com.prreview.mcpgateway.service;

import static org.junit.jupiter.api.Assertions.*;

import com.prreview.domain.model.ChangeSnapshot;
import com.prreview.mcpgateway.config.McpGatewayConfig;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class McpGatewayConcurrencyTest {
    @Test
    void twoPlatformSlotsAllowTwoDifferentJobsToReadConcurrently() throws Exception {
        exercise(2);
    }

    @Test
    void onePlatformSlotSerializesReadsAcrossJobs() throws Exception {
        exercise(1);
    }

    private void exercise(int slots) throws Exception {
        var registration =
                new McpGatewayConfig.Registration(
                        "github",
                        "stdio",
                        "com.prreview.mcp.server.github.GitHubMcpServer",
                        "0.2.0",
                        "2025-11-25",
                        List.of("owner/repo"));
        var entered = new CountDownLatch(slots);
        var release = new CountDownLatch(1);
        var active = new AtomicInteger();
        var peak = new AtomicInteger();
        McpGatewayService.Factory factory =
                (server, repository, number) ->
                        new McpGatewayService.Backend() {
                            @Override
                            public void verifyCatalog() {}

                            @Override
                            public ChangeSnapshot snapshot() {
                                return new ChangeSnapshot(
                                        "github",
                                        repository,
                                        number,
                                        "",
                                        "",
                                        "a".repeat(40),
                                        "b".repeat(40),
                                        repository,
                                        "open",
                                        0,
                                        true,
                                        List.of());
                            }

                            @Override
                            public String fileAtCommit(String sha, String path) throws Exception {
                                peak.accumulateAndGet(active.incrementAndGet(), Math::max);
                                entered.countDown();
                                try {
                                    if (!release.await(3, TimeUnit.SECONDS))
                                        throw new TimeoutException();
                                    return "pinned";
                                } finally {
                                    active.decrementAndGet();
                                }
                            }

                            @Override
                            public boolean isCurrent(ChangeSnapshot snapshot) {
                                return true;
                            }

                            @Override
                            public void close() {}
                        };
        try (var gateway =
                        new McpGatewayService(
                                new McpGatewayConfig("worker", 4, List.of(registration)),
                                factory,
                                ignored -> {},
                                slots,
                                4,
                                Duration.ofSeconds(2));
                var threads = Executors.newFixedThreadPool(2)) {
            String a = UUID.randomUUID().toString(), b = UUID.randomUUID().toString();
            gateway.invoke("worker", "github.get_change_snapshot", scope(a, false));
            gateway.invoke("worker", "github.get_change_snapshot", scope(b, false));
            var first =
                    threads.submit(
                            () ->
                                    gateway.invoke(
                                            "worker", "github.get_file_at_commit", scope(a, true)));
            var second =
                    threads.submit(
                            () ->
                                    gateway.invoke(
                                            "worker", "github.get_file_at_commit", scope(b, true)));
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertEquals(slots, peak.get());
            } finally {
                release.countDown();
            }
            assertEquals(Map.of("content", "pinned"), first.get(3, TimeUnit.SECONDS));
            assertEquals(Map.of("content", "pinned"), second.get(3, TimeUnit.SECONDS));
            assertEquals(slots, peak.get());
            var wrong = new java.util.HashMap<>(scope(a, true));
            wrong.put("repository", "owner/other");
            assertThrows(
                    SecurityException.class,
                    () -> gateway.invoke("worker", "github.get_file_at_commit", wrong));
        }
    }

    private Map<String, Object> scope(String id, boolean file) {
        var result =
                new java.util.HashMap<String, Object>(
                        Map.of("jobId", id, "repository", "owner/repo", "number", 1));
        if (file) {
            result.put("sha", "b".repeat(40));
            result.put("path", "A.java");
        }
        return result;
    }
}
