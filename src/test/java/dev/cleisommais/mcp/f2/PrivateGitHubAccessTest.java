package dev.cleisommais.mcp.f2;

import com.sun.net.httpserver.HttpServer;
import dev.cleisommais.mcp.f4.GitHubAccess;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PrivateGitHubAccessTest {
    @Test void privateRepositoryIsMaskedWithoutCredentialAndReadableWithScopedCredential() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String token = "synthetic-installation-token";
        server.createContext("/", exchange -> {
            if (!("Bearer " + token).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                exchange.sendResponseHeaders(404, -1); exchange.close(); return;
            }
            Object result = exchange.getRequestURI().getPath().endsWith("/files") ? List.of() : Map.of(
                    "head", Map.of("sha", "b".repeat(40), "repo", Map.of("full_name", "owner/private")),
                    "base", Map.of("sha", "a".repeat(40)), "changed_files", 0, "title", "Private change", "body", "", "state", "open");
            byte[] body = Json.write(result).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            var base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
            assertTrue(assertThrows(java.io.IOException.class, () -> new PublicGitHubApi(base, "owner/private", 1).snapshot()).getMessage().contains("404"));
            var access = new GitHubAccess(base, repository -> { assertEquals("owner/private", repository); return token; });
            var snapshot = new PublicGitHubApi(base, "owner/private", 1, access).snapshot();
            assertEquals("owner/private", snapshot.repository()); assertTrue(snapshot.enumerationComplete());
            assertFalse(Json.write(snapshot).contains(token));
        } finally { server.stop(0); }
    }
}
