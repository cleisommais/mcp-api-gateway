package dev.cleisommais.mcp.f3;

import dev.cleisommais.mcp.f2.Json;
import io.modelcontextprotocol.json.TypeRef;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;

/** Administrative configuration loaded at startup, never from a model/tool argument. */
public record GatewayConfig(String principal, int maxOpenJobs, List<Registration> servers) {
    public record Registration(String id, String transport, String mainClass, String version,
                               String protocol, List<String> repositories, String credentialMode) {
        public Registration {
            if (credentialMode == null) credentialMode = "anonymous";
            if (!List.of("anonymous", "app").contains(credentialMode)) throw new IllegalArgumentException("Unapproved credential mode");
            repositories = List.copyOf(repositories);
        }
        public Registration(String id, String transport, String mainClass, String version, String protocol, List<String> repositories) {
            this(id, transport, mainClass, version, protocol, repositories, "anonymous");
        }
    }

    public GatewayConfig {
        if (principal == null || !principal.matches("[a-zA-Z0-9-]{1,64}") || maxOpenJobs < 1 || maxOpenJobs > 32
                || servers == null || servers.isEmpty()) throw new IllegalArgumentException("Invalid gateway configuration");
        var repositories = new HashSet<String>();
        var ids = new HashSet<String>();
        for (var server : servers) {
            if (!"stdio".equals(server.transport()) || !"dev.cleisommais.mcp.f2.GitHubMcpServer".equals(server.mainClass())
                    || !"0.2.0".equals(server.version()) || !"2025-11-25".equals(server.protocol())
                    || server.id() == null || !server.id().matches("[a-zA-Z0-9-]{1,64}") || !ids.add(server.id())
                    || server.repositories() == null || server.repositories().isEmpty()) {
                throw new IllegalArgumentException("Unapproved server registration");
            }
            for (String repository : server.repositories()) {
                if (!repository.matches("[A-Za-z0-9_-]+/[A-Za-z0-9_.-]+") || repository.endsWith("/..")
                        || repository.endsWith("/.") || !repositories.add(repository)) {
                    throw new IllegalArgumentException("Invalid or ambiguously routed repository");
                }
            }
        }
        servers = List.copyOf(servers);
    }

    public static GatewayConfig read(Path path) throws Exception {
        return Json.read(Files.readString(path), new TypeRef<>() {});
    }

    Registration route(String repository) {
        return servers.stream().filter(server -> server.repositories().contains(repository)).findFirst()
                .orElseThrow(() -> new SecurityException("Repository is not registered"));
    }
}
