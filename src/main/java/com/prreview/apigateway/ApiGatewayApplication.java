package com.prreview.apigateway;

import com.prreview.apigateway.controller.GitHubWebhookController;
import com.prreview.apigateway.controller.HealthController;
import com.prreview.apigateway.controller.PlatformWebhookController;
import com.prreview.apigateway.controller.ReviewController;
import com.prreview.apigateway.security.GitHubWebhookPolicy;
import com.prreview.apigateway.service.ReviewRequestService;
import com.prreview.apigateway.service.WebhookAdmissionService;
import com.prreview.common.config.SecretReader;
import com.prreview.mcp.client.GatewayMcpClient;
import com.prreview.persistence.repository.PostgresJobRepository;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;

/**
 * HTTP composition root. Controllers own routes; services own workflows; the repository owns SQL.
 */
public final class ApiGatewayApplication implements AutoCloseable {
    private final HttpServer server;
    private final WebhookAdmissionService webhookAdmissions;
    private final java.util.concurrent.ExecutorService executor =
            java.util.concurrent.Executors.newFixedThreadPool(4);

    public ApiGatewayApplication(
            int port, GitHubWebhookPolicy policy, GitHubWebhookController.Queue queue)
            throws Exception {
        this(port, policy, queue, null, null);
    }

    public ApiGatewayApplication(
            int port,
            GitHubWebhookPolicy policy,
            GitHubWebhookController.Queue queue,
            ReviewRequestService reviews,
            String apiToken)
            throws Exception {
        this(port, policy, queue, reviews, apiToken, null);
    }

    private ApiGatewayApplication(
            int port,
            GitHubWebhookPolicy policy,
            GitHubWebhookController.Queue queue,
            ReviewRequestService reviews,
            String apiToken,
            WebhookAdmissionService admissions)
            throws Exception {
        webhookAdmissions = admissions;
        server = HttpServer.create(new InetSocketAddress(port), 16);
        server.setExecutor(executor);
        server.createContext("/health", new HealthController());
        server.createContext("/webhooks/github", new GitHubWebhookController(policy, queue));
        if (reviews != null)
            server.createContext("/api/reviews", new ReviewController(reviews, apiToken));
        if (admissions != null) {
            var config = policy.config();
            for (String platform : java.util.List.of("gitlab", "bitbucket")) {
                String secret =
                        SecretReader.optional(
                                platform.toUpperCase(java.util.Locale.ROOT) + "_WEBHOOK_SECRET");
                if (!secret.isBlank())
                    server.createContext(
                            "/webhooks/" + platform,
                            new PlatformWebhookController(platform, secret, config, admissions));
            }
        }
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(1);
        executor.shutdownNow();
        if (webhookAdmissions != null) webhookAdmissions.close();
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("API_INTERNAL_PORT", "8081"));
        if (args.length == 1 && args[0].equals("--health")) {
            var response =
                    java.net.http.HttpClient.newHttpClient()
                            .send(
                                    java.net.http.HttpRequest.newBuilder(
                                                    URI.create(
                                                            "http://localhost:" + port + "/health"))
                                            .GET()
                                            .timeout(java.time.Duration.ofSeconds(3))
                                            .build(),
                                    java.net.http.HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != 200) System.exit(1);
            return;
        }
        var config =
                GitHubWebhookPolicy.read(
                        Path.of(
                                System.getenv()
                                        .getOrDefault(
                                                "REPOSITORIES_CONFIG",
                                                "/config/repositories.json")));
        var jobs = PostgresJobRepository.environment();
        long installation =
                Long.parseLong(System.getenv().getOrDefault("GITHUB_INSTALLATION_ID", "0"));
        String gatewayToken = SecretReader.required("MCP_GATEWAY_TOKEN");
        var gateway =
                URI.create(
                        System.getenv().getOrDefault("MCP_GATEWAY_URL", "http://mcp-gateway:8080"));
        var reviews =
                new ReviewRequestService(
                        jobs,
                        config,
                        (platform, repository, number, requestId) ->
                                new GatewayMcpClient(
                                        gateway,
                                        gatewayToken,
                                        platform,
                                        repository,
                                        number,
                                        requestId),
                        installation);
        var app =
                new ApiGatewayApplication(
                        port,
                        new GitHubWebhookPolicy(
                                SecretReader.required("GITHUB_WEBHOOK_SECRET"),
                                installation,
                                config),
                        jobs::enqueue,
                        reviews,
                        SecretReader.required("API_TOKEN"),
                        new WebhookAdmissionService(jobs, reviews, config));
        Runtime.getRuntime().addShutdownHook(new Thread(app::close));
        System.out.println(
                "api-gateway listening on "
                        + port
                        + "; routes: GET /health, POST /api/reviews, GET /api/reviews/{id}, POST"
                        + " /webhooks/github");
        new java.util.concurrent.CountDownLatch(1).await();
    }
}
