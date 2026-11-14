package dev.cleisommais.mcp.f4;

import com.sun.net.httpserver.HttpServer;
import dev.cleisommais.mcp.f2.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.net.URI;
import java.net.InetSocketAddress;
import java.time.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class GitHubAppCredentialTest {
    @Test void identifiesMissingAppSettingsWithoutExposingValues(@TempDir Path directory) throws Exception {
        Path key = Files.writeString(directory.resolve("app.pem"), "synthetic-key-not-read-during-preflight");
        var valid = Map.of("GITHUB_APP_ID", "123", "GITHUB_INSTALLATION_ID", "42",
                "GITHUB_PRIVATE_KEY_PATH", key.toString());
        var environment = new HashMap<>(valid);
        environment.put("GATEWAY_TOKEN", "unrelated-secret");
        var forwarded = GitHubAccess.appEnvironment(environment);
        assertEquals(4, forwarded.size());
        assertEquals("app", forwarded.get("GITHUB_AUTH_MODE"));
        assertFalse(forwarded.containsKey("GATEWAY_TOKEN"));
        for (String setting : valid.keySet()) {
            var missing = new HashMap<>(valid); missing.remove(setting);
            var error = assertThrows(GitHubAccess.ConfigurationException.class, () -> GitHubAccess.appEnvironment(missing));
            assertTrue(error.getMessage().startsWith(setting + " is missing"));
        }
        for (String value : List.of("0", "-1", "not-an-installation", "9999999999999999999999999999")) {
            var invalid = new HashMap<>(valid); invalid.put("GITHUB_INSTALLATION_ID", value);
            var error = assertThrows(GitHubAccess.ConfigurationException.class, () -> GitHubAccess.appEnvironment(invalid));
            assertTrue(error.getMessage().startsWith("GITHUB_INSTALLATION_ID must be"));
        }
        environment.put("GITHUB_APP_ID", "synthetic-sensitive-value");
        var error = assertThrows(GitHubAccess.ConfigurationException.class, () -> GitHubAccess.appEnvironment(environment));
        assertTrue(error.getMessage().startsWith("GITHUB_APP_ID must be"));
        assertFalse(error.getMessage().contains("synthetic-sensitive-value"));
        Files.delete(key);
        error = assertThrows(GitHubAccess.ConfigurationException.class, () -> GitHubAccess.appEnvironment(valid));
        assertTrue(error.getMessage().startsWith("GITHUB_PRIVATE_KEY_PATH must point to a readable PEM file"));
    }
    @Test void signsAppJwtScopesAndRefreshesInstallationToken(@TempDir Path directory) throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); var keys = generator.generateKeyPair();
        var keyPath = directory.resolve("app.pem");
        Files.writeString(keyPath, "-----BEGIN PRIVATE KEY-----\n" + Base64.getEncoder().encodeToString(keys.getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----\n");
        var now = new AtomicReference<>(Instant.parse("2026-11-13T20:00:00Z"));
        Clock clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        var count = new AtomicInteger(); var scope = new AtomicReference<Map<String, Object>>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                String[] jwt = exchange.getRequestHeaders().getFirst("Authorization").substring(7).split("\\.");
                var signature = Signature.getInstance("SHA256withRSA"); signature.initVerify(keys.getPublic());
                signature.update((jwt[0] + "." + jwt[1]).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                assertTrue(signature.verify(Base64.getUrlDecoder().decode(jwt[2])));
                var claims = Json.object(new String(Base64.getUrlDecoder().decode(jwt[1]), java.nio.charset.StandardCharsets.UTF_8));
                assertEquals("123", claims.get("iss")); assertEquals(now.get().getEpochSecond() + 540, ((Number) claims.get("exp")).longValue());
                Object result;
                if (exchange.getRequestMethod().equals("POST")) {
                    scope.set(Json.object(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)));
                    result = Map.of("token", "synthetic-token-" + count.incrementAndGet(), "expires_at", now.get().plusSeconds(3600).toString());
                } else result = Map.of("slug", "poc-review");
                byte[] bytes = Json.write(result).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(201, bytes.length); try (var output = exchange.getResponseBody()) { output.write(bytes); }
            } catch (Exception error) { throw new java.io.IOException(error); }
        }); server.start();
        try {
            var credential = new GitHubAccess.AppCredential(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), "123", 42, keyPath, "owner/private", false, clock);
            assertEquals(credential.token("owner/private"), credential.token("owner/private")); assertEquals(1, count.get());
            assertEquals(List.of("private"), scope.get().get("repositories"));
            assertEquals("read", Json.map(scope.get().get("permissions")).get("pull_requests"));
            assertThrows(SecurityException.class, () -> credential.token("outside/repo"));
            now.set(now.get().plusSeconds(3550)); credential.token("owner/private"); assertEquals(2, count.get());
            credential.invalidate(); credential.token("owner/private"); assertEquals(3, count.get());
            assertEquals("poc-review[bot]", credential.actor());
            var rsa = (java.security.interfaces.RSAPrivateCrtKey) keys.getPrivate();
            var encoded = new java.io.ByteArrayOutputStream();
            for (var integer : List.of(java.math.BigInteger.ZERO, rsa.getModulus(), rsa.getPublicExponent(), rsa.getPrivateExponent(),
                    rsa.getPrimeP(), rsa.getPrimeQ(), rsa.getPrimeExponentP(), rsa.getPrimeExponentQ(), rsa.getCrtCoefficient())) {
                encoded.writeBytes(der(2, integer.toByteArray()));
            }
            Files.writeString(keyPath, "-----BEGIN RSA PRIVATE KEY-----\n" + Base64.getEncoder().encodeToString(der(48, encoded.toByteArray())) + "\n-----END RSA PRIVATE KEY-----\n");
            var writer = new GitHubAccess.AppCredential(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), "123", 42, keyPath, "owner/private", true, clock);
            writer.token("owner/private"); assertEquals("write", Json.map(scope.get().get("permissions")).get("pull_requests"));
        } finally { server.stop(0); }
    }
    @Test void refreshes401ReadOnceButNeverRetriesAnAmbiguousWrite() throws Exception {
        var attempts = new AtomicInteger(); var invalidations = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            attempts.incrementAndGet();
            boolean valid = exchange.getRequestMethod().equals("GET") && invalidations.get() > 0;
            byte[] body = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(valid ? 200 : 401, body.length); try (var output = exchange.getResponseBody()) { output.write(body); }
        }); server.start();
        try {
            var credential = new GitHubAccess.Credential() {
                @Override public String token(String repo) { return "synthetic"; }
                @Override public void invalidate() { invalidations.incrementAndGet(); }
            };
            var api = new GitHubAccess(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), credential);
            api.request("owner/repo", "GET", "repos/owner/repo/pulls/1", null); assertEquals(2, attempts.get());
            assertThrows(GitHubAccess.RequestException.class, () -> api.request("owner/repo", "POST", "repos/owner/repo/issues/1/comments", Map.of("body", "test")));
            assertEquals(3, attempts.get()); assertEquals(1, invalidations.get());
        } finally { server.stop(0); }
    }
    private static byte[] der(int tag, byte[] content) {
        var bytes = new java.io.ByteArrayOutputStream(); bytes.write(tag);
        if (content.length < 128) bytes.write(content.length);
        else if (content.length < 256) { bytes.write(0x81); bytes.write(content.length); }
        else { bytes.write(0x82); bytes.write(content.length >> 8); bytes.write(content.length); }
        bytes.writeBytes(content); return bytes.toByteArray();
    }
}
