package dev.cleisommais.mcp.f5;

import dev.cleisommais.mcp.f2.ReadOnlyReviewServer;

/** A repository-bound GitLab.com adapter; no model-accessible writes. */
public final class GitLabMcpServer {
    private GitLabMcpServer() {}
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected project path and MR IID");
        ReadOnlyReviewServer.run("gitlab-review", "0.1.0", new GitLabApi(args[0], Integer.parseInt(args[1])));
    }
}
