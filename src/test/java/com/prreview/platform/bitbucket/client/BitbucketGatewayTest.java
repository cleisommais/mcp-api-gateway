package com.prreview.platform.bitbucket.client;

import static com.prreview.platform.bitbucket.client.BitbucketFixture.*;

import static org.junit.jupiter.api.Assertions.*;

import com.prreview.agent.service.ReviewEngine;
import com.prreview.apigateway.dto.CreateReviewRequest;
import com.prreview.common.json.Json;
import com.prreview.domain.model.ChangeSnapshot;
import com.prreview.domain.model.ModelGeneration;
import com.prreview.domain.model.ReviewJob;
import com.prreview.domain.port.ReviewModel;
import com.prreview.mcp.client.GatewayMcpClient;
import com.prreview.mcp.client.StdioMcpClient;
import com.prreview.mcpgateway.McpGatewayApplication;
import com.prreview.mcpgateway.config.McpGatewayConfig;
import com.prreview.mcpgateway.config.ReviewToolCatalog;
import com.prreview.mcpgateway.service.McpGatewayService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

class BitbucketGatewayTest {
    @TempDir Path directory;

    private static McpGatewayConfig.Registration registration(String credentialMode) {
        return new McpGatewayConfig.Registration(
                "private-bitbucket",
                "stdio",
                "com.prreview.mcp.server.bitbucket.BitbucketMcpServer",
                "0.1.0",
                "2025-11-25",
                List.of(REPO),
                credentialMode);
    }

    @Test
    void realStdioCatalogAndGatewayHttpFeedTheExistingReviewEngine() throws Exception {
        var audits = new ArrayList<Map<String, Object>>();
        try (var fixture = new BitbucketFixture(BitbucketFixture::standard);
                var catalog = new StdioMcpClient("bitbucket", REPO, 1, Map.of())) {
            ReviewToolCatalog.validate(catalog.catalog());
            assertEquals("bitbucket-review", catalog.initialized().serverInfo().name());
            var config = new McpGatewayConfig("poc-worker", 8, List.of(registration("token")));
            McpGatewayService.Factory factory =
                    (registration, repository, number) -> {
                        var source =
                                new BitbucketChangeSource(
                                        fixture.access(false), repository, number);
                        return new McpGatewayService.Backend() {
                            public void verifyCatalog() {
                                ReviewToolCatalog.validate(catalog.catalog());
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
            String secret = "fixture-" + "x".repeat(40);
            try (var gateway = new McpGatewayApplication(config, secret, 0, factory, audits::add);
                    var source =
                            new GatewayMcpClient(
                                    URI.create("http://localhost:" + gateway.port()),
                                    secret,
                                    "bitbucket",
                                    REPO,
                                    1,
                                    UUID.randomUUID())) {
                ReviewModel model =
                        new ReviewModel() {
                            public String name() {
                                return "deterministic-bitbucket-fixture";
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
                                                                        "evidence",
                                                                        "return amount * 100;",
                                                                        "explanation",
                                                                        "Already-cent amount is"
                                                                            + " multiplied"
                                                                            + " again.")))),
                                        1,
                                        1,
                                        1);
                            }
                        };
                var report = new ReviewEngine(8192, 4).review(source, model);
                assertEquals("bitbucket", report.get("platform"));
                assertEquals("complete", report.get("status"));
                assertEquals(1, Json.list(report.get("findings")).size());
                assertEquals(BASE, report.get("baseSha"));
                assertEquals(HEAD, report.get("headSha"));
            }
            assertTrue(
                    audits.stream()
                            .anyMatch(
                                    event ->
                                            "private-bitbucket".equals(event.get("server"))
                                                    && "allowed".equals(event.get("decision"))));
        }
    }

    @Test
    void subprocessReceivesOnlyItsReadCredentialAndEmail() throws Exception {
        Path read = directory.resolve("read-token");
        Files.writeString(read, "fixture-read-token");
        var parent =
                Map.of(
                        "BITBUCKET_READ_TOKEN_FILE",
                        read.toString(),
                        "BITBUCKET_EMAIL",
                        "fixture@example.invalid",
                        "BITBUCKET_PUBLISH_TOKEN_FILE",
                        "/secret/write",
                        "GITLAB_READ_TOKEN_FILE",
                        "/secret/gitlab",
                        "OPENAI_API_KEY_FILE",
                        "/secret/inference");
        assertEquals(
                Map.of(
                        "BITBUCKET_READ_TOKEN_FILE",
                        read.toString(),
                        "BITBUCKET_EMAIL",
                        "fixture@example.invalid"),
                McpGatewayService.backendEnvironment(registration("token"), parent));
        assertEquals(
                Map.of(), McpGatewayService.backendEnvironment(registration("anonymous"), parent));
        Files.writeString(read, "");
        assertThrows(
                IllegalArgumentException.class,
                () -> McpGatewayService.backendEnvironment(registration("token"), parent));
    }

    @Test
    void platformToolsCannotReuseAnotherPlatformsJobScope() throws Exception {
        var github =
                new McpGatewayConfig.Registration(
                        "github",
                        "stdio",
                        "com.prreview.mcp.server.github.GitHubMcpServer",
                        "0.2.0",
                        "2025-11-25",
                        List.of(REPO));
        var config = new McpGatewayConfig("poc-worker", 8, List.of(github, registration("token")));
        try (var service =
                new McpGatewayService(
                        config,
                        (registration, repository, number) ->
                                new McpGatewayService.Backend() {
                                    public void verifyCatalog() {}

                                    public ChangeSnapshot snapshot() {
                                        return new ChangeSnapshot(
                                                registration.platform(),
                                                repository,
                                                number,
                                                "Change",
                                                "",
                                                BASE,
                                                HEAD,
                                                repository,
                                                "open",
                                                0,
                                                true,
                                                List.of());
                                    }

                                    public String fileAtCommit(String sha, String path) {
                                        throw new AssertionError("Cross-platform read");
                                    }

                                    public boolean isCurrent(ChangeSnapshot snapshot) {
                                        return true;
                                    }

                                    public void close() {}
                                },
                        event -> {})) {
            var arguments =
                    new HashMap<String, Object>(
                            Map.of(
                                    "jobId",
                                    UUID.randomUUID().toString(),
                                    "repository",
                                    REPO,
                                    "number",
                                    1));
            service.invoke("poc-worker", "github.get_change_snapshot", arguments);
            assertThrows(
                    SecurityException.class,
                    () -> service.invoke("poc-worker", "bitbucket.get_change_snapshot", arguments));
            arguments.put("sha", HEAD);
            assertThrows(
                    SecurityException.class,
                    () -> service.invoke("poc-worker", "bitbucket.get_change_state", arguments));
        }
    }

    @Test
    void validatesApiRequestsAndBuildsBitbucketCommentUrls() {
        assertEquals("bitbucket", new CreateReviewRequest("bitbucket", REPO, 1).platform());
        assertThrows(
                IllegalArgumentException.class,
                () -> new CreateReviewRequest("bitbucket", "workspace/group/repo", 1));
        var job =
                new ReviewJob(
                        UUID.randomUUID(),
                        "bitbucket",
                        1,
                        REPO,
                        1,
                        0,
                        BASE,
                        HEAD,
                        "main",
                        null,
                        "succeeded",
                        1,
                        null,
                        null,
                        "{}",
                        null,
                        "published",
                        42L,
                        null,
                        null);
        assertEquals(
                "https://bitbucket.org/" + REPO + "/pull-requests/1#comment-42",
                job.publicationUrl());
    }
}
