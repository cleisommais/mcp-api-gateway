package dev.cleisommais.mcp.f4;

import dev.cleisommais.mcp.f2.Json;
import java.util.Map;
import java.util.UUID;

/** Local administrative CLI; no unauthenticated HTTP job/retry endpoint is exposed. */
public final class QueueAdmin {
    private QueueAdmin() {}
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !java.util.List.of("show", "retry").contains(args[0])) throw new IllegalArgumentException("Usage: show|retry JOB_UUID");
        var store = JobStore.environment(); var id = UUID.fromString(args[1]);
        if (args[0].equals("retry")) {
            try (var connection = store.connection(); var statement = connection.prepareStatement("""
                    UPDATE jobs SET state='queued',run_owner=NULL,lease_until=NULL,failure_code=NULL
                    WHERE id=? AND state IN ('failed','publication_uncertain')
                    """)) { statement.setObject(1, id); System.out.println("Requeued jobs: " + statement.executeUpdate()); }
        }
        var job = store.find(id);
        if (job == null) throw new IllegalArgumentException("Job not found");
        System.out.println(Json.write(Map.of("id", job.id(), "repository", job.repository(), "number", job.number(),
                "headSha", job.headSha(), "state", job.state(), "attempts", job.attempts(), "publicationState", job.publicationState(),
                "publicationId", job.publicationId() == null ? "" : job.publicationId().toString())));
    }
}
