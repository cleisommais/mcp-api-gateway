package com.prreview.agent.service;

import static com.prreview.platform.gitlab.client.GitLabFixture.*;

import static org.junit.jupiter.api.Assertions.*;

import com.prreview.common.json.Json;
import com.prreview.domain.model.ChangeSnapshot;
import com.prreview.domain.model.ModelGeneration;
import com.prreview.domain.port.ChangeSource;
import com.prreview.domain.port.ReviewModel;
import com.prreview.mcp.client.GatewayMcpClient;
import com.prreview.mcp.client.StdioMcpClient;
import com.prreview.mcp.server.github.GitHubMcpServer;
import com.prreview.mcpgateway.McpGatewayApplication;
import com.prreview.mcpgateway.config.McpGatewayConfig;
import com.prreview.mcpgateway.config.ReviewToolCatalog;
import com.prreview.mcpgateway.service.McpGatewayService;
import com.prreview.platform.github.client.GitHubChangeSource;
import com.prreview.platform.gitlab.client.GitLabChangeSource;
import com.prreview.platform.gitlab.client.GitLabFixture;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class PlatformContractTest {
    private static final String REPOSITORY = "owner/repo";
    private static final String SECRET = "test-only-" + "x".repeat(40);

    private static McpGatewayConfig config() {
        return new McpGatewayConfig(
                "poc-worker",
                8,
                List.of(
                        new McpGatewayConfig.Registration(
                                "github",
                                "stdio",
                                GitHubMcpServer.class.getName(),
                                "0.2.0",
                                "2025-11-25",
                                List.of(REPOSITORY)),
                        new McpGatewayConfig.Registration(
                                "gitlab",
                                "stdio",
                                "com.prreview.mcp.server.gitlab.GitLabMcpServer",
                                "0.1.0",
                                "2025-11-25",
                                List.of(REPOSITORY))));
    }

    @Test
    void identicalChangesUseBothAdaptersAndRealGatewayHttpWithUnchangedAnalysisRules()
            throws Exception {
        try (var fixture =
                        new GitLabFixture(
                                exchange -> {
                                    String path = exchange.getRequestURI().toString();
                                    if (path.startsWith("/repos/")) {
                                        if (path.contains("/files?"))
                                            return ok(
                                                    List.of(
                                                            Map.of(
                                                                    "filename",
                                                                    "Money.java",
                                                                    "status",
                                                                    "modified",
                                                                    "patch",
                                                                    PATCH,
                                                                    "additions",
                                                                    1,
                                                                    "deletions",
                                                                    1)));
                                        if (path.contains("/contents/"))
                                            return ok(
                                                    Map.of(
                                                            "type",
                                                            "file",
                                                            "encoding",
                                                            "base64",
                                                            "size",
                                                            20,
                                                            "content",
                                                            Base64.getEncoder()
                                                                    .encodeToString(
                                                                            "return amount * 100;"
                                                                                    .getBytes())));
                                        return ok(
                                                Map.of(
                                                        "head",
                                                        Map.of(
                                                                "sha",
                                                                HEAD,
                                                                "repo",
                                                                Map.of("full_name", REPOSITORY)),
                                                        "base",
                                                        Map.of("sha", BASE, "ref", "main"),
                                                        "changed_files",
                                                        1,
                                                        "title",
                                                        "Change",
                                                        "body",
                                                        "",
                                                        "state",
                                                        "open"));
                                    }
                                    if (path.contains("/diffs?"))
                                        return ok(List.of(file("Money.java")));
                                    if (path.contains("/repository/files/"))
                                        return ok(
                                                Map.of(
                                                        "file_path",
                                                        "Money.java",
                                                        "commit_id",
                                                        HEAD,
                                                        "encoding",
                                                        "base64",
                                                        "size",
                                                        20,
                                                        "content",
                                                        Base64.getEncoder()
                                                                .encodeToString(
                                                                        "return amount * 100;"
                                                                                .getBytes())));
                                    return ok(metadata("1"));
                                });
                var githubCatalog = new StdioMcpClient(REPOSITORY, 1);
                var gitlabCatalog = new StdioMcpClient("gitlab", REPOSITORY, 1, Map.of())) {
            // Both independently implemented adapters must expose precisely the same reviewed
            // contract.
            ReviewToolCatalog.validate(githubCatalog.catalog());
            ReviewToolCatalog.validate(gitlabCatalog.catalog());
            // The GitLab fixture exposes its loopback origin for the GitHub adapter as well.
            var audits = new ArrayList<Map<String, Object>>();
            McpGatewayService.Factory factory =
                    (registration, repository, number) -> {
                        ChangeSource api;
                        if (registration.platform().equals("gitlab"))
                            api = new GitLabChangeSource(fixture.access(false), repository, number);
                        else {
                            var github =
                                    new GitHubChangeSource(fixture.origin(), repository, number);
                            api =
                                    new ChangeSource() {
                                        public ChangeSnapshot snapshot() throws Exception {
                                            return github.snapshot();
                                        }

                                        public String fileAtCommit(String sha, String path)
                                                throws Exception {
                                            return github.fileAtCommit(sha, path);
                                        }

                                        public boolean isCurrent(ChangeSnapshot snapshot)
                                                throws Exception {
                                            return github.isCurrent();
                                        }

                                        public void close() {}
                                    };
                        }
                        var source = api;
                        return new McpGatewayService.Backend() {
                            public void verifyCatalog() {
                                ReviewToolCatalog.validate(
                                        registration.platform().equals("github")
                                                ? githubCatalog.catalog()
                                                : gitlabCatalog.catalog());
                            }

                            public ChangeSnapshot snapshot() throws Exception {
                                return source.snapshot();
                            }

                            public String fileAtCommit(String sha, String path) throws Exception {
                                return source.fileAtCommit(sha, path);
                            }

                            public boolean isCurrent(ChangeSnapshot snapshot) throws Exception {
                                return source.isCurrent(snapshot);
                            }

                            public void close() {
                                source.close();
                            }
                        };
                    };
            ReviewModel model =
                    new ReviewModel() {
                        public String name() {
                            return "deterministic-contract-fixture";
                        }

                        public ModelGeneration generate(
                                String system, String context, Map<String, Object> schema)
                                throws Exception {
                            assertTrue(context.contains(HEAD));
                            assertTrue(context.contains("headContent"));
                            return new ModelGeneration(
                                    Json.write(
                                            Map.of(
                                                    "summary",
                                                    "Unit mismatch",
                                                    "findings",
                                                    List.of(
                                                            Map.of(
                                                                    "file",
                                                                    "Money.java",
                                                                    "line",
                                                                    1,
                                                                    "severity",
                                                                    "MEDIUM",
                                                                    "explanation",
                                                                    "Already-cent amount is"
                                                                            + " multiplied again.",
                                                                    "evidence",
                                                                    "return amount * 100;")))),
                                    1,
                                    1,
                                    1);
                        }
                    };
            try (var gateway =
                    new McpGatewayApplication(config(), SECRET, 0, factory, audits::add)) {
                Map<String, Object> first = null;
                for (String platform : List.of("github", "gitlab")) {
                    try (var source =
                            new GatewayMcpClient(
                                    URI.create("http://localhost:" + gateway.port()),
                                    SECRET,
                                    platform,
                                    REPOSITORY,
                                    1,
                                    UUID.randomUUID())) {
                        var report = new ReviewEngine(8192, 4).review(source, model);
                        assertEquals(platform, report.get("platform"));
                        assertEquals("complete", report.get("status"));
                        assertEquals(1, ((List<?>) report.get("findings")).size());
                        if (first == null) first = report;
                        else
                            for (String key :
                                    List.of(
                                            "findings",
                                            "coverage",
                                            "promptVersion",
                                            "schemaVersion",
                                            "baseSha",
                                            "headSha"))
                                assertEquals(first.get(key), report.get(key), key);
                    }
                }
            }
            assertTrue(
                    audits.stream()
                            .anyMatch(
                                    e ->
                                            "gitlab".equals(e.get("server"))
                                                    && "allowed".equals(e.get("decision"))));
        }
    }

    @Test
    void sameRepositoryNameOnDifferentPlatformsCannotReuseJobScope() throws Exception {
        var opens = new java.util.concurrent.atomic.AtomicInteger();
        try (var service =
                new McpGatewayService(
                        config(),
                        (registration, repo, number) -> {
                            opens.incrementAndGet();
                            return new McpGatewayService.Backend() {
                                public void verifyCatalog() {}

                                public ChangeSnapshot snapshot() {
                                    return new ChangeSnapshot(
                                            registration.platform(),
                                            repo,
                                            number,
                                            "Change",
                                            "",
                                            BASE,
                                            HEAD,
                                            repo,
                                            "open",
                                            0,
                                            true,
                                            List.of());
                                }

                                public String fileAtCommit(String sha, String path) {
                                    fail("Cross-platform code read");
                                    return "";
                                }

                                public boolean isCurrent(ChangeSnapshot snapshot) {
                                    return true;
                                }

                                public void close() {}
                            };
                        },
                        e -> {})) {
            var scope =
                    new java.util.HashMap<String, Object>(
                            Map.of(
                                    "jobId",
                                    UUID.randomUUID().toString(),
                                    "repository",
                                    REPOSITORY,
                                    "number",
                                    1));
            service.invoke("poc-worker", "github.get_change_snapshot", scope);
            assertThrows(
                    SecurityException.class,
                    () -> service.invoke("poc-worker", "gitlab.get_change_snapshot", scope));
            scope.put("sha", HEAD);
            assertThrows(
                    SecurityException.class,
                    () -> service.invoke("poc-worker", "gitlab.get_change_state", scope));
            assertEquals(1, opens.get());
        }
    }
}
