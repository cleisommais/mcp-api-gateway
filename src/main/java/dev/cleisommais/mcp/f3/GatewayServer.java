package dev.cleisommais.mcp.f3;

import dev.cleisommais.mcp.f2.Json;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.startup.Tomcat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Map;
import java.util.function.Consumer;

/** Authenticated Streamable HTTP upstream; controlled stdio subprocesses downstream. */
public final class GatewayServer implements AutoCloseable {
    private final Tomcat tomcat = new Tomcat();
    private final McpSyncServer server;
    private final GatewayService service;

    public GatewayServer(GatewayConfig config, String secret, int port, GatewayService.Factory factory,
                         Consumer<Map<String, Object>> audit) throws Exception {
        if (secret == null || secret.length() < 32) throw new IllegalArgumentException("A generated gateway lab token is required");
        var env = System.getenv();
        service = new GatewayService(config, factory, audit,
                dev.cleisommais.mcp.f7.CallGate.setting(env, "PLATFORM_MAX_CONCURRENT", 1, 1, 16),
                dev.cleisommais.mcp.f7.CallGate.setting(env, "PLATFORM_MAX_WAITING", 8, 0, 128),
                java.time.Duration.ofSeconds(dev.cleisommais.mcp.f7.CallGate.setting(env, "PLATFORM_WAIT_SECONDS", 30, 1, 180)));
        var transport = HttpServletStreamableServerTransportProvider.builder().mcpEndpoint("/mcp").build();
        var tools = GatewayCatalog.schemas(config.servers().stream().map(GatewayConfig.Registration::platform).collect(java.util.stream.Collectors.toSet()))
                .entrySet().stream().map(entry -> McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder(entry.getKey(), entry.getValue()).description("Scoped read-only review operation: " + entry.getKey()).build())
                .callHandler((exchange, request) -> {
                    try {
                        var result = service.invoke(config.principal(), request.name(), request.arguments());
                        return McpSchema.CallToolResult.builder().structuredContent(result).addTextContent(Json.write(result)).isError(false).build();
                    } catch (Exception error) {
                        return McpSchema.CallToolResult.builder().isError(true)
                                .addTextContent(error instanceof SecurityException || error instanceof dev.cleisommais.mcp.f4.GitHubAccess.ConfigurationException
                                        ? error.getMessage() : "Downstream operation failed; inspect gateway audit").build();
                    }
                }).build()).toList();
        server = McpServer.sync(transport).serverInfo("review-mcp-gateway", "0.3.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build()).tools(tools).build();
        var directory = Files.createTempDirectory("review-gateway-").toFile();
        tomcat.setBaseDir(directory.getAbsolutePath());
        tomcat.setPort(port);
        tomcat.getConnector();
        var context = tomcat.addContext("", directory.getAbsolutePath());
        var wrapper = Tomcat.addServlet(context, "mcp", new HttpServlet() {
            @Override protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
                String header = request.getHeader("Authorization");
                boolean valid = header != null && MessageDigest.isEqual(("Bearer " + secret).getBytes(StandardCharsets.UTF_8), header.getBytes(StandardCharsets.UTF_8));
                if (!valid || request.getHeader("Origin") != null) {
                    audit.accept(Map.of("principal", "anonymous", "tool", "http", "decision", "denied", "reason", "authentication-or-origin"));
                    response.setStatus(valid ? 403 : 401);
                    return;
                }
                transport.service((ServletRequest) request, (ServletResponse) response);
            }
        });
        wrapper.setAsyncSupported(true);
        context.addServletMappingDecoded("/mcp", "mcp");
        Tomcat.addServlet(context, "health", new HttpServlet() {
            @Override protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
                response.setContentType("text/plain"); response.getWriter().write("ready");
            }
        });
        context.addServletMappingDecoded("/health", "health");
        tomcat.start();
    }

    public int port() { return tomcat.getConnector().getLocalPort(); }
    @Override public void close() {
        server.closeGracefully();
        service.close();
        try { tomcat.stop(); tomcat.destroy(); }
        catch (Exception error) { throw new IllegalStateException("Gateway shutdown failed", error); }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "--health".equals(args[0])) {
            var response = java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(
                    java.net.URI.create("http://localhost:8080/health")).timeout(java.time.Duration.ofSeconds(3)).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != 200) System.exit(1);
            return;
        }
        var config = GatewayConfig.read(Path.of(System.getenv().getOrDefault("GATEWAY_CONFIG", "/config/gateway.json")));
        var gateway = new GatewayServer(config, System.getenv("GATEWAY_TOKEN"),
                Integer.parseInt(System.getenv().getOrDefault("GATEWAY_PORT", "8080")), GatewayService.stdioFactory(), event -> {
                    try { System.out.println(Json.write(event)); }
                    catch (IOException error) { throw new IllegalStateException("Audit serialization failed", error); }
                });
        Runtime.getRuntime().addShutdownHook(new Thread(gateway::close));
        gateway.tomcat.getServer().await();
    }
}
