package dev.cleisommais.mcp.f5;

import dev.cleisommais.mcp.f2.Json;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/** Explicit manual publication of a saved report, using a separate write credential. */
public final class PublishGitLabReport {
    private PublishGitLabReport() {}
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: REPORT_JSON_PATH");
        Path reportPath = Path.of(args[0]);
        var report = Json.object(Files.readString(reportPath));
        Path journal = reportPath.resolveSibling(reportPath.getFileName() + ".publication.json");
        // Retain the lock file: deleting it can let another process lock a different inode.
        try (var channel = FileChannel.open(reportPath.resolveSibling(reportPath.getFileName() + ".publication.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE); var lock = channel.tryLock()) {
            if (lock == null) throw new IllegalStateException("This report already has an active publisher");
            long id = new GitLabPublisher(GitLabAccess.environment(true)).publish(Json.text(report, "repository"),
                    Math.toIntExact(GitLabApi.id(report, "number")), report, Files.exists(journal), () -> {
                        Files.writeString(journal, Json.write(Map.of("state", "attempted", "headSha", report.get("headSha"))));
                    });
            Files.writeString(journal, Json.write(Map.of("state", "published", "headSha", report.get("headSha"), "noteId", id)));
            System.out.println("GitLab summary note: " + id);
        }
    }
}
