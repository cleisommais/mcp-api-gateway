package dev.cleisommais.mcp.f2;

import java.util.List;
import java.util.Map;

/** Platform-independent data passed from MCP retrieval to review orchestration. */
public final class ReviewData {
    private ReviewData() {}

    public record FileChange(String path, String status, String patch, boolean patchComplete,
                             int additions, int deletions, String category) {}

    public record Snapshot(String platform, String repository, int number, String title, String description,
                           String baseSha, String headSha, String headRepository, String state,
                           int expectedFiles, boolean enumerationComplete, List<FileChange> files,
                           boolean draft, String baseBranch) {
        public Snapshot { if (baseBranch == null) baseBranch = ""; }
        public Snapshot(String platform, String repository, int number, String title, String description,
                        String baseSha, String headSha, String headRepository, String state,
                        int expectedFiles, boolean enumerationComplete, List<FileChange> files) {
            this(platform, repository, number, title, description, baseSha, headSha, headRepository, state,
                    expectedFiles, enumerationComplete, files, false, "");
        }
    }

    public record Finding(String file, int line, String severity, String explanation, String evidence) {}
    public record Coverage(String file, String status, String reason) {}
    public record RejectedFinding(String file, String reason, Map<String, Object> candidate) {}
    public record Generation(String content, long durationMillis, long promptTokens, long outputTokens) {}

    public interface Source extends AutoCloseable {
        Snapshot snapshot() throws Exception;
        String fileAtCommit(String sha, String path) throws Exception;
        boolean isCurrent(Snapshot snapshot) throws Exception;
        @Override void close();
    }

    public interface Model {
        Generation generate(String system, String context, Map<String, Object> schema) throws Exception;
        String name();
    }
}
