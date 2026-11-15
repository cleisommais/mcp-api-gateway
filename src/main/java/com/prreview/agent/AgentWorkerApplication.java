package com.prreview.agent;

import com.prreview.agent.service.ReviewWorker;

/** Agent process entry point. It has no public HTTP listener. */
public final class AgentWorkerApplication {
    private AgentWorkerApplication() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--health")) {
            if (!java.nio.file.Files.isRegularFile(
                    java.nio.file.Path.of("/tmp/agent-worker.ready"))) System.exit(1);
            return;
        }
        ReviewWorker.run(args);
    }
}
