package com.prreview.platform.github.client;

import com.prreview.common.json.Json;

import io.modelcontextprotocol.json.TypeRef;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

/** Installation credentials are resolved locally and never returned by an MCP tool. */
public final class GitHubApiClient {
    public static final class ConfigurationException extends IllegalStateException {
        public ConfigurationException() {
            super(
                    "Configure the GitHub App ID, installation ID and readable PEM file for this"
                            + " connection");
        }

        public ConfigurationException(String message) {
            super(message);
        }
    }

    public static final class RequestException extends IOException {
        private final int status;

        RequestException(int status) {
            super(
                    "GitHub HTTP "
                            + status
                            + "; check installation, repository permissions and rate limits");
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    public interface Credential {
        String token(String repository) throws Exception;

        default void invalidate() {}

        default String actor() throws Exception {
            throw new IllegalStateException("No publishing identity");
        }
    }

    private final URI base;
    private final Credential credential;
    private final HttpClient http =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();

    public GitHubApiClient(URI base, Credential credential) {
        this.base = base;
        this.credential = credential;
    }

    public static GitHubApiClient anonymous(URI base) {
        return new GitHubApiClient(base, repository -> "");
    }

    public static Map<String, String> appEnvironment(Map<String, String> environment) {
        var result = new java.util.HashMap<String, String>();
        result.put("GITHUB_AUTH_MODE", "app");
        for (String name :
                java.util.List.of(
                        "GITHUB_APP_ID", "GITHUB_INSTALLATION_ID", "GITHUB_PRIVATE_KEY_PATH")) {
            String value = environment.get(name);
            if (value == null || value.isBlank()) {
                throw new ConfigurationException(
                        name
                                + " is missing; configure it in .env and recreate the"
                                + " gateway/worker containers");
            }
            result.put(name, value);
        }
        if (!result.get("GITHUB_APP_ID").matches("[1-9][0-9]*")) {
            throw new ConfigurationException(
                    "GITHUB_APP_ID must be the positive numeric App ID from the App settings");
        }
        try {
            String installation = result.get("GITHUB_INSTALLATION_ID");
            if (!installation.matches("[1-9][0-9]*") || Long.parseLong(installation) < 1)
                throw new NumberFormatException();
        } catch (NumberFormatException error) {
            throw new ConfigurationException(
                    "GITHUB_INSTALLATION_ID must be the positive installation ID from the"
                            + " installation URL; 0 is unconfigured");
        }
        try {
            Path key = Path.of(result.get("GITHUB_PRIVATE_KEY_PATH"));
            if (!Files.isRegularFile(key) || !Files.isReadable(key))
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException error) {
            throw new ConfigurationException(
                    "GITHUB_PRIVATE_KEY_PATH must point to a readable PEM file; check the"
                            + " secrets/github-app.pem mount");
        }
        return Map.copyOf(result);
    }

    public static GitHubApiClient environment(String repository, boolean publishing) {
        String mode = System.getenv().getOrDefault("GITHUB_AUTH_MODE", "anonymous");
        if (mode.equals("anonymous") && !publishing)
            return anonymous(URI.create("https://api.github.com/"));
        if (!mode.equals("app"))
            throw new IllegalArgumentException("GitHub App configuration is required");
        var environment = appEnvironment(System.getenv());
        try {
            return new GitHubApiClient(
                    URI.create("https://api.github.com/"),
                    new AppCredential(
                            URI.create("https://api.github.com/"),
                            environment.get("GITHUB_APP_ID"),
                            Long.parseLong(environment.get("GITHUB_INSTALLATION_ID")),
                            Path.of(environment.get("GITHUB_PRIVATE_KEY_PATH")),
                            repository,
                            publishing,
                            Clock.systemUTC()));
        } catch (Exception error) {
            throw new ConfigurationException();
        }
    }

    public String actor() throws Exception {
        return credential.actor();
    }

    public Object request(String repository, String method, String path, Object payload)
            throws Exception {
        if (!path.startsWith("repos/" + repository + "/"))
            throw new SecurityException("GitHub request outside repository scope");
        for (int attempt = 0; attempt < 2; attempt++) {
            String token = credential.token(repository);
            var builder =
                    HttpRequest.newBuilder(base.resolve(path))
                            .timeout(Duration.ofSeconds(25))
                            .header("Accept", "application/vnd.github+json")
                            .header("User-Agent", "mcp-review-poc")
                            .header("X-GitHub-Api-Version", "2022-11-28");
            if (!token.isBlank()) builder.header("Authorization", "Bearer " + token);
            builder.method(
                    method,
                    payload == null
                            ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(Json.write(payload)));
            if (payload != null) builder.header("Content-Type", "application/json");
            var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            String body;
            try (var input = response.body()) {
                byte[] bytes = input.readNBytes(2_000_001);
                if (bytes.length > 2_000_000)
                    throw new IOException("GitHub response exceeded the byte limit");
                body = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            }
            if (response.statusCode() == 401 && attempt == 0 && method.equals("GET")) {
                credential.invalidate();
                continue;
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RequestException(response.statusCode());
            }
            return body.isBlank() ? Map.of() : Json.read(body, new TypeRef<Object>() {});
        }
        throw new IOException("GitHub authentication failed");
    }

    public static final class AppCredential implements Credential {
        private final URI base;
        private final String appId;
        private final long installationId;
        private final java.security.PrivateKey key;
        private final String repository;
        private final boolean publishing;
        private final Clock clock;
        private String cached;
        private Instant expires = Instant.EPOCH;
        private String actor;
        private final HttpClient http =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

        public AppCredential(
                URI base,
                String appId,
                long installationId,
                Path keyPath,
                String repository,
                boolean publishing,
                Clock clock)
                throws Exception {
            if (appId == null
                    || !appId.matches("[0-9]+")
                    || installationId < 1
                    || !repository.matches("[A-Za-z0-9_-]+/[A-Za-z0-9_.-]+"))
                throw new IllegalArgumentException("Invalid GitHub App scope");
            this.base = base;
            this.appId = appId;
            this.installationId = installationId;
            this.repository = repository;
            this.publishing = publishing;
            this.clock = clock;
            String pem = Files.readString(keyPath);
            byte[] der = Base64.getMimeDecoder().decode(pem.replaceAll("-----[A-Z ]+-----", ""));
            if (pem.contains("BEGIN RSA PRIVATE KEY")) {
                byte[] algorithm =
                        java.util.HexFormat.of().parseHex("020100300d06092a864886f70d0101010500");
                byte[] octets = der(4, der);
                byte[] wrapped = new byte[algorithm.length + octets.length];
                System.arraycopy(algorithm, 0, wrapped, 0, algorithm.length);
                System.arraycopy(octets, 0, wrapped, algorithm.length, octets.length);
                der = der(48, wrapped);
            }
            key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        }

        @Override
        public synchronized String token(String requestedRepository) throws Exception {
            if (!repository.equals(requestedRepository))
                throw new SecurityException(
                        "Installation token requested outside registered repository");
            if (cached == null || !clock.instant().plusSeconds(60).isBefore(expires)) {
                var permissions =
                        Map.of("contents", "read", "pull_requests", publishing ? "write" : "read");
                var response =
                        appRequest(
                                "POST",
                                "app/installations/" + installationId + "/access_tokens",
                                Map.of(
                                        "repositories",
                                        java.util.List.of(repository.split("/")[1]),
                                        "permissions",
                                        permissions));
                cached = Json.text(response, "token");
                expires = Instant.parse(Json.text(response, "expires_at"));
                if (!expires.isAfter(clock.instant().plusSeconds(60)))
                    throw new IOException("Installation token expires too soon");
            }
            return cached;
        }

        @Override
        public synchronized void invalidate() {
            cached = null;
            expires = Instant.EPOCH;
        }

        @Override
        public synchronized String actor() throws Exception {
            if (actor == null) actor = Json.text(appRequest("GET", "app", null), "slug") + "[bot]";
            return actor;
        }

        private Map<String, Object> appRequest(String method, String path, Object payload)
                throws Exception {
            var encode = Base64.getUrlEncoder().withoutPadding();
            String header =
                    encode.encodeToString(
                            Json.write(Map.of("alg", "RS256", "typ", "JWT"))
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            long now = clock.instant().getEpochSecond();
            String claims =
                    encode.encodeToString(
                            Json.write(Map.of("iat", now - 60, "exp", now + 540, "iss", appId))
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String signed = header + "." + claims;
            var signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(key);
            signer.update(signed.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            String jwt = signed + "." + encode.encodeToString(signer.sign());
            var builder =
                    HttpRequest.newBuilder(base.resolve(path))
                            .timeout(Duration.ofSeconds(20))
                            .header("Authorization", "Bearer " + jwt)
                            .header("Accept", "application/vnd.github+json")
                            .header("User-Agent", "mcp-review-poc")
                            .header("X-GitHub-Api-Version", "2022-11-28");
            builder.method(
                    method,
                    payload == null
                            ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(Json.write(payload)));
            if (payload != null) builder.header("Content-Type", "application/json");
            var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new IOException("GitHub App authentication HTTP " + response.statusCode());
            return Json.object(response.body());
        }

        private static byte[] der(int tag, byte[] content) {
            byte[] length =
                    content.length < 128
                            ? new byte[] {(byte) content.length}
                            : content.length < 256
                                    ? new byte[] {(byte) 0x81, (byte) content.length}
                                    : new byte[] {
                                        (byte) 0x82,
                                        (byte) (content.length >> 8),
                                        (byte) content.length
                                    };
            byte[] result = new byte[1 + length.length + content.length];
            result[0] = (byte) tag;
            System.arraycopy(length, 0, result, 1, length.length);
            System.arraycopy(content, 0, result, 1 + length.length, content.length);
            return result;
        }
    }
}
