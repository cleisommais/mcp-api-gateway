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
            if (!List.of("anonymous", "app", "token").contains(credentialMode)) throw new IllegalArgumentException("Unapproved credential mode");
            repositories = List.copyOf(repositories);
        }
        public Registration(String id, String transport, String mainClass, String version, String protocol, List<String> repositories) {
            this(id, transport, mainClass, version, protocol, repositories, "anonymous");
        }
        public String platform() {
            return switch (mainClass) {
                case "dev.cleisommais.mcp.f2.GitHubMcpServer" -> "github";
                case "dev.cleisommais.mcp.f5.GitLabMcpServer" -> "gitlab";
                default -> throw new IllegalArgumentException("Unapproved server process");
            };
        }
    }

    public GatewayConfig {
        if (principal == null || !principal.matches("[a-zA-Z0-9-]{1,64}") || maxOpenJobs < 1 || maxOpenJobs > 32
                || servers == null || servers.isEmpty()) throw new IllegalArgumentException("Invalid gateway configuration");
        var repositories = new HashSet<String>();
        var ids = new HashSet<String>();
        for (var server : servers) {
            String platform = server.platform();
            if (!"stdio".equals(server.transport())
                    || !(platform.equals("github") ? "0.2.0" : "0.1.0").equals(server.version())
                    || !(platform.equals("github") ? List.of("anonymous", "app") : List.of("anonymous", "token")).contains(server.credentialMode())
                    || !"2025-11-25".equals(server.protocol())
                    || server.id() == null || !server.id().matches("[a-zA-Z0-9-]{1,64}") || !ids.add(server.id())
                    || server.repositories() == null || server.repositories().isEmpty()) {
                throw new IllegalArgumentException("Unapproved server registration");
            }
            for (String repository : server.repositories()) {
                dev.cleisommais.mcp.f5.GitLabAccess.validateRepository(repository);
                if ((platform.equals("github") && repository.split("/").length != 2)
                        || !repositories.add(platform + ":" + repository)) {
                    throw new IllegalArgumentException("Invalid or ambiguously routed repository");
                }
            }
        }
        servers = List.copyOf(servers);
    }

    public static GatewayConfig read(Path path) throws Exception {
        return Json.read(Files.readString(path), new TypeRef<>() {});
    }

    Registration route(String platform, String repository) {
        return servers.stream().filter(server -> server.platform().equals(platform) && server.repositories().contains(repository)).findFirst()
                .orElseThrow(() -> new SecurityException("Repository is not registered"));
    }
}
