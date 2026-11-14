package dev.cleisommais.mcp.f4;

import com.sun.net.httpserver.HttpServer;
import dev.cleisommais.mcp.f2.Json;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Map;

/** Edge accepts signed events and commits a job before acknowledging receipt. */
public final class WebhookEdge implements AutoCloseable {
    @FunctionalInterface public interface Queue { JobStore.Receipt enqueue(WebhookPolicy.Event event) throws Exception; }
    private final HttpServer server;
    private final java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(4);

    public WebhookEdge(int port, WebhookPolicy policy, Queue queue) throws Exception {
        server = HttpServer.create(new InetSocketAddress(port), 16); server.setExecutor(executor);
        server.createContext("/health", exchange -> {
            byte[] body = "ready".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.createContext("/webhooks/github", exchange -> {
            int status = 500; Map<String, Object> response = Map.of("error", "request-failed");
            try {
                if (!exchange.getRequestURI().getPath().equals("/webhooks/github") || !exchange.getRequestMethod().equals("POST")) {
                    status = 405; response = Map.of("error", "POST-required");
                } else {
                    byte[] bytes = exchange.getRequestBody().readNBytes(1_000_001);
                    if (bytes.length > 1_000_000) { status = 413; response = Map.of("error", "body-too-large"); }
                    else {
                        var headers = exchange.getRequestHeaders();
                        var event = policy.validate(headers.getFirst("X-GitHub-Delivery"), headers.getFirst("X-GitHub-Event"),
                                headers.getFirst("X-Hub-Signature-256"), bytes);
                        var receipt = queue.enqueue(event);
                        status = receipt.jobId() != null && !receipt.duplicate() ? 202 : 200;
                        response = Map.of("outcome", receipt.outcome(), "duplicate", receipt.duplicate(),
                                "jobId", receipt.jobId() == null ? "" : receipt.jobId().toString());
                    }
                }
            } catch (SecurityException error) { status = 403; response = Map.of("error", "webhook-not-authorized"); }
            catch (IllegalArgumentException error) { status = 400; response = Map.of("error", "invalid-event"); }
            catch (Exception error) { status = 503; response = Map.of("error", "dependency-or-configuration-unavailable"); }
            byte[] body = Json.write(response).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
    }
    public int port() { return server.getAddress().getPort(); }
    @Override public void close() { server.stop(1); executor.shutdownNow(); }
    public static void main(String[] args) throws Exception {
        var config = WebhookPolicy.read(Path.of(System.getenv().getOrDefault("AUTOMATION_CONFIG", "/config/automation.json")));
        var store = JobStore.environment();
        var edge = new WebhookEdge(Integer.parseInt(System.getenv().getOrDefault("EDGE_PORT", "8081")),
                new WebhookPolicy(System.getenv("WEBHOOK_SECRET"), Long.parseLong(System.getenv().getOrDefault("GITHUB_INSTALLATION_ID", "0")), config), store::enqueue);
        Runtime.getRuntime().addShutdownHook(new Thread(edge::close));
        new java.util.concurrent.CountDownLatch(1).await();
    }
}
