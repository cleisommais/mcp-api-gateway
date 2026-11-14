package dev.cleisommais.mcp.f3;

import dev.cleisommais.mcp.f2.Json;
import dev.cleisommais.mcp.f2.McpReviewSource;
import dev.cleisommais.mcp.f2.ReviewData;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** Small in-memory lab gateway. Authorization precedes every downstream operation. */
public final class GatewayService implements AutoCloseable {
    public interface Backend extends ReviewData.Source { void verifyCatalog(); }
    @FunctionalInterface public interface Factory {
        Backend open(GatewayConfig.Registration registration, String repository, int number) throws Exception;
    }
    private record Job(String principal, GatewayConfig.Registration server, ReviewData.Snapshot snapshot, Backend backend) {}
    private final GatewayConfig config;
    private final Factory factory;
    private final Consumer<Map<String, Object>> audit;
    private final Map<String, Job> jobs = new HashMap<>();

    public GatewayService(GatewayConfig config, Factory factory, Consumer<Map<String, Object>> audit) {
        this.config = config;
        this.factory = factory;
        this.audit = audit;
    }

    public static Factory stdioFactory() {
        return (registration, repository, number) -> {
            var environment = new HashMap<String, String>();
            if (registration.credentialMode().equals("app")) {
                environment.putAll(dev.cleisommais.mcp.f4.GitHubAccess.appEnvironment(System.getenv()));
            }
            var source = new McpReviewSource(repository, number, environment);
            return new Backend() {
                @Override public void verifyCatalog() {
                    var initialized = source.initialized();
                    if (!"github-review".equals(initialized.serverInfo().name()) || !registration.version().equals(initialized.serverInfo().version())
                            || !registration.protocol().equals(initialized.protocolVersion())) {
                        throw new SecurityException("Downstream version/protocol changed");
                    }
                    GatewayCatalog.validate(source.catalog());
                }
                @Override public ReviewData.Snapshot snapshot() throws Exception { return source.snapshot(); }
                @Override public String fileAtCommit(String sha, String path) throws Exception { return source.fileAtCommit(sha, path); }
                @Override public boolean isCurrent(ReviewData.Snapshot snapshot) throws Exception { return source.isCurrent(snapshot); }
                @Override public void close() { source.close(); }
            };
        };
    }

    public synchronized Object invoke(String principal, String tool, Map<String, Object> arguments) throws Exception {
        String decision = "denied", reason = "policy", serverId = "unresolved", sha = "unpinned";
        long started = System.nanoTime();
        try {
            if (!config.principal().equals(principal)) throw new SecurityException("Unknown workload identity");
            var schema = GatewayCatalog.schemas().get(tool);
            if (schema == null) throw new SecurityException("Tool is not approved");
            if (!arguments.keySet().equals(Json.map(schema.get("properties")).keySet())) throw new SecurityException("Unexpected or missing arguments");
            String jobId = Json.text(arguments, "jobId");
            if (!UUID.fromString(jobId).toString().equals(jobId)) throw new SecurityException("Invalid job identity");
            String repository = Json.text(arguments, "repository");
            Object rawNumber = arguments.get("number");
            if (!(rawNumber instanceof Integer || rawNumber instanceof Long) || ((Number) rawNumber).longValue() < 1
                    || ((Number) rawNumber).longValue() > Integer.MAX_VALUE) throw new SecurityException("Invalid PR number");
            int number = ((Number) rawNumber).intValue();
            var registration = config.route(repository);
            serverId = registration.id();
            var job = jobs.get(jobId);
            if (job == null) {
                if (!tool.equals("github.get_change_snapshot")) throw new SecurityException("Unknown or closed job");
                if (jobs.size() >= config.maxOpenJobs()) throw new IllegalStateException("Open review limit reached; close an existing review");
                var backend = factory.open(registration, repository, number);
                try {
                    backend.verifyCatalog();
                    var snapshot = backend.snapshot();
                    if (!repository.equals(snapshot.repository()) || number != snapshot.number() || !"github".equals(snapshot.platform())
                            || !snapshot.headSha().matches("[a-f0-9]{40}") || !snapshot.baseSha().matches("[a-f0-9]{40}")) {
                        throw new SecurityException("Downstream returned a different resource");
                    }
                    job = new Job(principal, registration, snapshot, backend);
                    jobs.put(jobId, job);
                } catch (Exception error) { backend.close(); throw error; }
            }
            if (!job.principal().equals(principal) || !job.server().id().equals(serverId)
                    || !job.snapshot().repository().equals(repository) || job.snapshot().number() != number) {
                throw new SecurityException("Job resource scope mismatch");
            }
            sha = job.snapshot().headSha();
            if (!tool.equals("github.get_change_snapshot") && !sha.equals(Json.text(arguments, "sha"))) {
                throw new SecurityException("Commit is outside the pinned job scope");
            }
            Object result;
            if (tool.equals("github.close_review")) {
                jobs.remove(jobId);
                job.backend().close();
                result = Map.of("closed", true);
            } else {
                job.backend().verifyCatalog();
                result = switch (tool) {
                    case "github.get_change_snapshot" -> {
                        var snapshot = new java.util.LinkedHashMap<>(Json.object(Json.write(job.snapshot())));
                        snapshot.put("_gateway", Map.of("server", job.server().id(), "credentialMode", job.server().credentialMode()));
                        yield snapshot;
                    }
                    case "github.get_change_state" -> Map.of("current", job.backend().isCurrent(job.snapshot()));
                    case "github.get_file_at_commit" -> {
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
        } catch (Exception error) {
            if (!(error instanceof SecurityException || error instanceof IllegalArgumentException)) decision = "failed";
            reason = error instanceof SecurityException ? error.getMessage() : error.getClass().getSimpleName();
            throw error;
        } finally {
            audit.accept(Map.of("at", Instant.now().toString(), "principal", principal, "tool", tool,
                    "jobId", safeId(arguments.get("jobId")), "server", serverId, "sha", sha,
                    "repository", safeId(arguments.get("repository")), "decision", decision, "reason", reason,
                    "durationMillis", (System.nanoTime() - started) / 1_000_000));
        }
    }

    private static String safeId(Object value) {
        return value instanceof String text && text.matches("[a-zA-Z0-9_./-]{1,160}") ? text : "invalid-or-absent";
    }

    private static void validatePath(String path) {
        if (path.isBlank() || path.startsWith("/") || path.contains("\\") || path.indexOf('\0') >= 0) throw new SecurityException("Invalid path");
        for (String part : path.split("/", -1)) if (Set.of("", ".", "..").contains(part)) throw new SecurityException("Path traversal denied");
    }

    @Override public synchronized void close() {
        jobs.values().forEach(job -> job.backend().close());
        jobs.clear();
    }
}
