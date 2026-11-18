package com.prreview.mcp.server.gitlab;

import com.prreview.mcp.server.ReadOnlyReviewMcpServer;
import com.prreview.platform.gitlab.client.GitLabChangeSource;

/** A repository-bound GitLab.com adapter; no model-accessible writes. */
public final class GitLabMcpServer {
    private GitLabMcpServer() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2)
            throw new IllegalArgumentException("Expected project path and MR IID");
        ReadOnlyReviewMcpServer.run(
                "gitlab-review",
                "0.1.0",
                new GitLabChangeSource(args[0], Integer.parseInt(args[1])));
    }
}
