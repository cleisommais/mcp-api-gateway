package com.prreview.mcpgateway.service;

import com.prreview.common.concurrent.ConcurrencyGate;
import com.prreview.common.config.SecretReader;
import com.prreview.common.json.Json;
import com.prreview.domain.model.ChangeSnapshot;
import com.prreview.domain.port.ChangeSource;
import com.prreview.mcp.client.StdioMcpClient;
import com.prreview.mcpgateway.config.McpGatewayConfig;
import com.prreview.mcpgateway.config.ReviewToolCatalog;
import com.prreview.platform.github.client.GitHubApiClient;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Repository tool gateway with scoped in-memory MCP contexts. Authorization precedes every
 * downstream operation.
 */
public final class McpGatewayService implements AutoCloseable {
    public interface Backend extends ChangeSource {
        void verifyCatalog();
    }

    @FunctionalInterface
    public interface Factory {
        Backend open(McpGatewayConfig.Registration registration, String repository, int number)
                throws Exception;
    }

    private record McpReviewContext(
            String principal,
            McpGatewayConfig.Registration server,
            ChangeSnapshot snapshot,
            Backend backend) {}

    private final McpGatewayConfig config;
    private final Factory factory;
    private final Consumer<Map<String, Object>> audit;
    private final Map<String, McpReviewContext> jobs = new HashMap<>();
    private final Map<String, ConcurrencyGate> platformGates = new HashMap<>();
    private boolean closed;

    public McpGatewayService(
            McpGatewayConfig config, Factory factory, Consumer<Map<String, Object>> audit) {
        this(config, factory, audit, 1, 8, java.time.Duration.ofSeconds(30));
    }

    public McpGatewayService(
            McpGatewayConfig config,
            Factory factory,
            Consumer<Map<String, Object>> audit,
            int platformCalls,
            int maxWaiting,
            java.time.Duration wait) {
        this.config = config;
        this.factory = factory;
        this.audit = audit;
        for (var registration : config.servers())
            platformGates.computeIfAbsent(
                    registration.platform(),
                    ignored -> new ConcurrencyGate(platformCalls, maxWaiting, wait));
    }

    public static Factory stdioFactory() {
        return (registration, repository, number) -> {
            var environment = new HashMap<String, String>();
            if (registration.credentialMode().equals("app")) {
                environment.putAll(GitHubApiClient.appEnvironment(System.getenv()));
            }
            if (registration.credentialMode().equals("token")) {
                String token = SecretReader.optional("GITLAB_READ_TOKEN");
                if (token == null || token.isBlank())
                    throw new IllegalArgumentException(
                            "GITLAB_READ_TOKEN is required for this connection");
                environment.put("GITLAB_READ_TOKEN_FILE", System.getenv("GITLAB_READ_TOKEN_FILE"));
            }
            var source =
                    new StdioMcpClient(registration.platform(), repository, number, environment);
            return new Backend() {
                @Override
                public void verifyCatalog() {
                    var initialized = source.initialized();
                    if (!(registration.platform() + "-review")
                                    .equals(initialized.serverInfo().name())
                            || !registration.version().equals(initialized.serverInfo().version())
                            || !registration.protocol().equals(initialized.protocolVersion())) {
                        throw new SecurityException("Downstream version/protocol changed");
                    }
                    ReviewToolCatalog.validate(source.catalog());
                }

                @Override
                public ChangeSnapshot snapshot() throws Exception {
                    return source.snapshot();
                }

                @Override
                public String fileAtCommit(String sha, String path) throws Exception {
                    return source.fileAtCommit(sha, path);
                }

                @Override
                public boolean isCurrent(ChangeSnapshot snapshot) throws Exception {
                    return source.isCurrent(snapshot);
                }

                @Override
                public void close() {
                    source.close();
                }
            };
        };
    }

    public Object invoke(String principal, String tool, Map<String, Object> arguments)
            throws Exception {
        String decision = "denied", reason = "policy", serverId = "unresolved", sha = "unpinned";
        long started = System.nanoTime();
        ConcurrencyGate.Permit permit = null;
        try {
            if (!config.principal().equals(principal))
                throw new SecurityException("Unknown workload identity");
            var schema = ReviewToolCatalog.schemas().get(tool);
            if (schema == null) throw new SecurityException("Tool is not approved");
            if (!arguments.keySet().equals(Json.map(schema.get("properties")).keySet()))
                throw new SecurityException("Unexpected or missing arguments");
            String jobId = Json.text(arguments, "jobId");
            if (!UUID.fromString(jobId).toString().equals(jobId))
                throw new SecurityException("Invalid job identity");
            String repository = Json.text(arguments, "repository");
            Object rawNumber = arguments.get("number");
            if (!(rawNumber instanceof Integer || rawNumber instanceof Long)
                    || ((Number) rawNumber).longValue() < 1
                    || ((Number) rawNumber).longValue() > Integer.MAX_VALUE)
                throw new SecurityException("Invalid PR number");
            int number = ((Number) rawNumber).intValue();
            String platform = tool.substring(0, tool.indexOf('.'));
            String operation = tool.substring(tool.indexOf('.') + 1);
            var registration = config.route(platform, repository);
            serverId = registration.id();
            permit = platformGates.get(platform).acquire();
            var job =
                    resolve(
                            principal,
                            registration,
                            repository,
                            number,
                            platform,
                            operation,
                            jobId);
            // A downstream client is sequential per job; separate jobs can use separate platform
            // permits.
            synchronized (job) {
                synchronized (jobs) {
                    if (jobs.get(jobId) != job)
                        throw new SecurityException("Unknown or closed job");
                }
                if (!job.principal().equals(principal)
                        || !job.server().id().equals(serverId)
                        || !job.snapshot().repository().equals(repository)
                        || job.snapshot().number() != number) {
                    throw new SecurityException("Job resource scope mismatch");
                }
                sha = job.snapshot().headSha();
                if (!operation.equals("get_change_snapshot")
                        && !sha.equals(Json.text(arguments, "sha"))) {
                    throw new SecurityException("Commit is outside the pinned job scope");
                }
                Object result;
                if (operation.equals("close_review")) {
                    synchronized (jobs) {
                        jobs.remove(jobId);
                    }
                    job.backend().close();
                    result = Map.of("closed", true);
                } else {
                    job.backend().verifyCatalog();
                    result =
                            switch (operation) {
                                case "get_change_snapshot" -> {
                                    var snapshot =
                                            new java.util.LinkedHashMap<>(
                                                    Json.object(Json.write(job.snapshot())));
                                    snapshot.put(
                                            "_gateway",
                                            Map.of(
                                                    "server",
                                                    job.server().id(),
                                                    "credentialMode",
                                                    job.server().credentialMode()));
                                    yield snapshot;
                                }
                                case "get_change_state" ->
                                        Map.of("current", job.backend().isCurrent(job.snapshot()));
                                case "get_file_at_commit" -> {
                                    String path = Json.text(arguments, "path");
                                    validatePath(path);
                                    yield Map.of("content", job.backend().fileAtCommit(sha, path));
                                }
                                default -> throw new SecurityException("Tool is not approved");
                            };
                }
                decision = "allowed";
                reason = "validated";
                return result;
            }
        } catch (Exception error) {
            if (!(error instanceof SecurityException || error instanceof IllegalArgumentException))
                decision = "failed";
            reason =
                    error instanceof SecurityException
                            ? error.getMessage()
                            : error.getClass().getSimpleName();
            throw error;
        } finally {
            if (permit != null) permit.close();
            var event =
                    new java.util.LinkedHashMap<String, Object>(
                            Map.of(
                                    "at",
                                    Instant.now().toString(),
                                    "principal",
                                    principal,
                                    "tool",
                                    tool,
                                    "jobId",
                                    safeId(arguments.get("jobId")),
                                    "server",
                                    serverId,
                                    "sha",
                                    sha,
                                    "repository",
                                    safeId(arguments.get("repository")),
                                    "decision",
                                    decision,
                                    "reason",
                                    reason,
                                    "durationMillis",
                                    (System.nanoTime() - started) / 1_000_000));
            event.put("admissionWaitMillis", permit == null ? 0 : permit.waitMillis());
            audit.accept(event);
        }
    }

    private McpReviewContext resolve(
            String principal,
            McpGatewayConfig.Registration registration,
            String repository,
            int number,
            String platform,
            String operation,
            String jobId)
            throws Exception {
        synchronized (jobs) {
            if (closed) throw new IllegalStateException("Gateway is closed");
            var existing = jobs.get(jobId);
            if (existing != null) return existing;
            if (!operation.equals("get_change_snapshot"))
                throw new SecurityException("Unknown or closed job");
            if (jobs.size() >= config.maxOpenJobs())
                throw new IllegalStateException(
                        "Open review limit reached; close an existing review");
            var backend = factory.open(registration, repository, number);
            try {
                backend.verifyCatalog();
                var snapshot = backend.snapshot();
                if (!repository.equals(snapshot.repository())
                        || number != snapshot.number()
                        || !platform.equals(snapshot.platform())
                        || !snapshot.headSha().matches("[a-f0-9]{40}")
                        || !snapshot.baseSha().matches("[a-f0-9]{40}")) {
                    throw new SecurityException("Downstream returned a different resource");
                }
                var job = new McpReviewContext(principal, registration, snapshot, backend);
                jobs.put(jobId, job);
                return job;
            } catch (Exception error) {
                backend.close();
                throw error;
            }
        }
    }

    private static String safeId(Object value) {
        return value instanceof String text && text.matches("[a-zA-Z0-9_./-]{1,160}")
                ? text
                : "invalid-or-absent";
    }

    private static void validatePath(String path) {
        if (path.isBlank()
                || path.startsWith("/")
                || path.contains("\\")
                || path.indexOf('\0') >= 0) throw new SecurityException("Invalid path");
        for (String part : path.split("/", -1))
            if (Set.of("", ".", "..").contains(part))
                throw new SecurityException("Path traversal denied");
    }

    @Override
    public void close() {
        java.util.List<McpReviewContext> active;
        synchronized (jobs) {
            closed = true;
            active = java.util.List.copyOf(jobs.values());
            jobs.clear();
        }
        for (var job : active)
            synchronized (job) {
                job.backend().close();
            }
    }
}
