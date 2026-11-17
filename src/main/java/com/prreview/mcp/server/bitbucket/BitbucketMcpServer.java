package com.prreview.mcp.server.bitbucket;

import com.prreview.mcp.server.ReadOnlyReviewMcpServer;
import com.prreview.platform.bitbucket.client.BitbucketChangeSource;

/** Repository-bound Bitbucket Cloud MCP reads; publication is outside MCP. */
public final class BitbucketMcpServer {
    private BitbucketMcpServer() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2)
            throw new IllegalArgumentException("Expected workspace/repository and PR number");
        ReadOnlyReviewMcpServer.run(
                "bitbucket-review",
                "0.1.0",
                new BitbucketChangeSource(args[0], Integer.parseInt(args[1])));
    }
}
