package com.prreview.platform.bitbucket.client;

import com.prreview.common.config.SecretReader;
import com.prreview.common.json.Json;

import io.modelcontextprotocol.json.TypeRef;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

/** Repository-bound Bitbucket Cloud REST v2 access; only the publisher can write comments. */
public final class BitbucketApiClient {
    public static final class RequestException extends IOException {
        private final int status;

        RequestException(int status) {
            super(
                    "Bitbucket HTTP "
                            + status
                            + "; check repository access, token scopes and rate limits");
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    private final URI base;
    private final String repository;
    private final String authorization;
    private final boolean publishing;
    private final HttpClient http =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();

    // Alternate origins are constructor-injected by tests, never accepted from MCP arguments.
    public BitbucketApiClient(
            URI base, String repository, String email, String token, boolean publishing) {
        validateRepository(repository);
        if (!base.getPath().endsWith("/")
                || base.getHost() == null
                || base.getUserInfo() != null
                || base.getQuery() != null
                || base.getFragment() != null)
            throw new IllegalArgumentException("Expected a Bitbucket API base URI");
        if (publishing && token.isBlank())
            throw new IllegalArgumentException("BITBUCKET_PUBLISH_TOKEN is required");
        if (email.contains(":") || email.contains("\r") || email.contains("\n"))
            throw new IllegalArgumentException("Invalid BITBUCKET_EMAIL");
        this.base = base;
        this.repository = repository;
        this.publishing = publishing;
        authorization =
                token.isBlank()
                        ? ""
                        : email.isBlank()
                                ? "Bearer " + token
                                : "Basic "
                                        + Base64.getEncoder()
                                                .encodeToString(
                                                        (email + ":" + token)
                                                                .getBytes(StandardCharsets.UTF_8));
    }

    public static BitbucketApiClient environment(String repository, boolean publishing) {
        return new BitbucketApiClient(
                URI.create("https://api.bitbucket.org/2.0/"),
                repository,
                System.getenv().getOrDefault("BITBUCKET_EMAIL", ""),
                SecretReader.optional(
                        publishing ? "BITBUCKET_PUBLISH_TOKEN" : "BITBUCKET_READ_TOKEN"),
                publishing);
    }

    public Object get(String path) throws Exception {
        return request("GET", path, null);
    }

    public Object request(String method, String path, Object payload) throws Exception {
        String body = exchange(method, path, payload, "application/json", 2_000_000);
        return body.isBlank() ? Map.of() : Json.read(body, new TypeRef<Object>() {});
    }

    public String text(String path, int limit) throws Exception {
        if (limit < 1 || limit > 2_000_000)
            throw new IllegalArgumentException("Invalid response limit");
        return exchange("GET", path, null, "text/plain", limit);
    }

    public String repositoryPath() {
        return "repositories/" + repository;
    }

    /** Validate server-supplied pagination URLs before they can receive credentials. */
    public String pagePath(String next, String expectedPath) {
        URI uri = scopedUri(next);
        if (!uri.getRawPath().equals(scopedUri(expectedPath).getRawPath()))
            throw new SecurityException("Bitbucket pagination changed the requested resource");
        return base.relativize(uri).toString();
    }

    private String exchange(String method, String path, Object payload, String accept, int limit)
            throws Exception {
        URI uri = scopedUri(path);
        String relativePath = base.relativize(uri).getRawPath();
        String comments =
                java.util.regex.Pattern.quote(repositoryPath())
                        + "/pullrequests/[1-9][0-9]*/comments";
        if (!method.equals("GET")
                && (!publishing
                        || uri.getQuery() != null
                        || !(method.equals("POST") && relativePath.matches(comments)
                                || method.equals("PUT")
                                        && relativePath.matches(comments + "/[1-9][0-9]*"))))
            throw new SecurityException(
                    "Only the controlled publisher can write PR summary comments");
        for (int redirects = 0; redirects <= 3; redirects++) {
            var builder =
                    HttpRequest.newBuilder(uri)
                            .timeout(Duration.ofSeconds(25))
                            .header("Accept", accept)
                            .header("User-Agent", "mcp-review-poc");
            if (!authorization.isBlank()) builder.header("Authorization", authorization);
            builder.method(
                    method,
                    payload == null
                            ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(Json.write(payload)));
            if (payload != null) builder.header("Content-Type", "application/json");
            var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (var input = response.body()) {
                int status = response.statusCode();
                if (Set.of(301, 302, 303, 307, 308).contains(status)) {
                    // PR diff endpoints redirect. Writes and cross-origin/LFS redirects are denied.
                    if (!method.equals("GET") || redirects == 3)
                        throw new IOException("Bitbucket redirect limit or write redirect denied");
                    String location =
                            response.headers()
                                    .firstValue("Location")
                                    .orElseThrow(
                                            () ->
                                                    new IOException(
                                                            "Missing Bitbucket redirect location"));
                    URI target = scopedUri(uri.resolve(location).toString());
                    String operation = uri.getPath().endsWith("/diffstat") ? "diffstat" : "diff";
                    if (!uri.getPath()
                                    .matches(
                                            java.util.regex.Pattern.quote(
                                                            base.getPath() + repositoryPath())
                                                    + "/pullrequests/[1-9][0-9]*/"
                                                    + operation)
                            || !target.getPath()
                                    .startsWith(
                                            base.getPath()
                                                    + repositoryPath()
                                                    + "/"
                                                    + operation
                                                    + "/"))
                        throw new SecurityException("Unexpected Bitbucket resource redirect");
                    uri = target;
                    continue;
                }
                if (status < 200 || status >= 300) throw new RequestException(status);
                byte[] bytes = input.readNBytes(limit + 1);
                if (bytes.length > limit)
                    throw new IOException("Bitbucket response exceeded the byte limit");
                return StandardCharsets.UTF_8
                        .newDecoder()
                        .decode(java.nio.ByteBuffer.wrap(bytes))
                        .toString();
            }
        }
        throw new IOException("Bitbucket redirect limit reached");
    }

    private URI scopedUri(String value) {
        URI uri = base.resolve(value);
        String root = base.getPath() + repositoryPath();
        if (!base.getScheme().equals(uri.getScheme())
                || !base.getRawAuthority().equals(uri.getRawAuthority())
                || uri.getUserInfo() != null
                || uri.getFragment() != null
                || !uri.normalize().equals(uri)
                || !(uri.getPath().startsWith(root + "/")
                        || publishing && uri.getPath().equals(base.getPath() + "user")))
            throw new SecurityException("Bitbucket request outside repository scope");
        for (String segment : uri.getPath().split("/"))
            if (segment.equals(".") || segment.equals("..") || segment.contains("\\"))
                throw new SecurityException("Invalid Bitbucket API path");
        return uri;
    }

    public static void validateRepository(String repository) {
        if (repository == null
                || !repository.matches("[A-Za-z0-9_-]+/[A-Za-z0-9_.-]+")
                || Set.of(".", "..").contains(repository.split("/")[1]))
            throw new IllegalArgumentException("Expected a Bitbucket workspace/repository slug");
    }

    public static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public static void validatePath(String path) {
        if (path == null
                || path.isBlank()
                || path.startsWith("/")
                || path.contains("\\")
                || path.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Invalid repository-relative path");
        for (String segment : path.split("/", -1))
            if (Set.of("", ".", "..").contains(segment))
                throw new IllegalArgumentException("Path traversal denied");
    }
}
