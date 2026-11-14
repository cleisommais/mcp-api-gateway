package dev.cleisommais.mcp.f4;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** PostgreSQL is both the durable record and the small POC work queue. */
public final class JobStore {
    public record Job(UUID id, long repositoryId, String repository, int number, long installationId,
                      String baseSha, String headSha, String baseBranch, String state, int attempts,
                      String report, String publicationState, Long publicationId) {}
    public record Receipt(UUID jobId, boolean duplicate, String outcome) {}
    private final String url, user, password, schema;

    public JobStore(String url, String user, String password, String schema) throws Exception {
        if (!schema.matches("[a-z][a-z0-9_]{0,62}")) throw new IllegalArgumentException("Invalid database schema");
        this.url = url; this.user = user; this.password = password; this.schema = schema;
        try (var connection = DriverManager.getConnection(url, user, password); var statement = connection.createStatement();
             var input = JobStore.class.getResourceAsStream("/db/schema.sql")) {
            if (input == null) throw new IllegalStateException("Missing database schema resource");
            // Edge and worker may start together on an empty database.
            long migrationLock = 0x4d49470000000000L ^ Integer.toUnsignedLong(schema.hashCode());
            statement.execute("SELECT pg_advisory_lock(" + migrationLock + ")");
            try {
                statement.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
                statement.execute("SET search_path TO " + schema);
                statement.execute(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            } finally { statement.execute("SELECT pg_advisory_unlock(" + migrationLock + ")"); }
        }
    }
    Connection connection() throws Exception {
        var connection = DriverManager.getConnection(url, user, password);
        try (var statement = connection.createStatement()) {
            statement.execute("SET search_path TO " + schema);
        }
        return connection;
    }
    public static JobStore environment() throws Exception {
        return new JobStore(System.getenv().getOrDefault("DATABASE_URL", "jdbc:postgresql://postgres:5432/mcp_review"),
                System.getenv().getOrDefault("DATABASE_USER", "mcp_review"), System.getenv("POSTGRES_PASSWORD"),
                System.getenv().getOrDefault("DATABASE_SCHEMA", "review"));
    }

    public Receipt enqueue(WebhookPolicy.Event event) throws Exception {
        try (var connection = connection()) {
            connection.setAutoCommit(false);
            try {
                // Serialize delivery/key decisions without storing raw webhook bodies.
                try (var lock = connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                    lock.setLong(1, resourceLock(event.repositoryId(), event.number()) ^ Long.MIN_VALUE); lock.execute();
                }
                try (var existing = connection.prepareStatement("SELECT payload_hash,job_id,outcome FROM deliveries WHERE delivery_id=?")) {
                    existing.setObject(1, UUID.fromString(event.deliveryId()));
                    try (var rows = existing.executeQuery()) {
                        if (rows.next()) {
                            if (!rows.getString(1).equals(event.payloadHash())) throw new SecurityException("Delivery ID reused with a different body");
                            var result = new Receipt((UUID) rows.getObject(2), true, rows.getString(3)); connection.commit(); return result;
                        }
                    }
                }
                UUID jobId = null;
                if (event.eligible()) {
                    jobId = UUID.randomUUID();
                    try (var insert = connection.prepareStatement("""
                            INSERT INTO jobs(id,repository_id,repository,pr_number,installation_id,base_sha,head_sha,base_branch,source_updated_at)
                            VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(repository_id,pr_number,base_sha,head_sha)
                            DO UPDATE SET repository=EXCLUDED.repository RETURNING id
                            """)) {
                        insert.setObject(1, jobId); insert.setLong(2, event.repositoryId()); insert.setString(3, event.repository());
                        insert.setInt(4, event.number()); insert.setLong(5, event.installationId()); insert.setString(6, event.baseSha());
                        insert.setString(7, event.headSha()); insert.setString(8, event.baseBranch()); insert.setObject(9, java.time.OffsetDateTime.ofInstant(event.updatedAt(), java.time.ZoneOffset.UTC));
                        try (var rows = insert.executeQuery()) { rows.next(); jobId = (UUID) rows.getObject(1); }
                    }
                    try (var supersede = connection.prepareStatement("""
                            UPDATE jobs SET state='superseded',updated_at=now() WHERE repository_id=? AND pr_number=?
                            AND state='queued' AND id<>? AND source_updated_at<?
                            """)) {
                        supersede.setLong(1, event.repositoryId()); supersede.setInt(2, event.number()); supersede.setObject(3, jobId);
                        supersede.setObject(4, java.time.OffsetDateTime.ofInstant(event.updatedAt(), java.time.ZoneOffset.UTC)); supersede.executeUpdate();
                    }
                }
                try (var delivery = connection.prepareStatement("INSERT INTO deliveries(delivery_id,payload_hash,event,action,outcome,job_id) VALUES(?,?,?,?,?,?)")) {
                    delivery.setObject(1, UUID.fromString(event.deliveryId())); delivery.setString(2, event.payloadHash());
                    delivery.setString(3, event.event()); delivery.setString(4, event.action()); delivery.setString(5, event.reason());
                    delivery.setObject(6, jobId); delivery.executeUpdate();
                }
                connection.commit(); return new Receipt(jobId, false, event.reason());
            } catch (Exception error) { connection.rollback(); throw error; }
        }
    }

    public Job claim(String owner, int leaseSeconds) throws Exception {
        try (var connection = connection(); var statement = connection.prepareStatement("""
                WITH candidate AS (SELECT id FROM jobs WHERE state='queued' ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1)
                UPDATE jobs SET state='running',attempts=attempts+1,run_owner=?,lease_until=now()+(? * interval '1 second'),updated_at=now()
                FROM candidate WHERE jobs.id=candidate.id RETURNING jobs.*
                """)) {
            statement.setString(1, owner); statement.setInt(2, leaseSeconds);
            try (var rows = statement.executeQuery()) { return rows.next() ? read(rows) : null; }
        }
    }
    public int recoverExpired() throws Exception {
        try (var connection = connection(); var statement = connection.createStatement()) {
            return statement.executeUpdate("UPDATE jobs SET state='queued',run_owner=NULL,lease_until=NULL,failure_code='lease-expired',updated_at=now() WHERE state IN ('running','publishing') AND lease_until<now()");
        }
    }
    public boolean heartbeat(UUID id, String owner, int leaseSeconds) throws Exception {
        try (var connection = connection(); var statement = connection.prepareStatement("UPDATE jobs SET lease_until=now()+(? * interval '1 second') WHERE id=? AND run_owner=? AND state IN ('running','publishing')")) {
            statement.setInt(1, leaseSeconds); statement.setObject(2, id); statement.setString(3, owner); return statement.executeUpdate() == 1;
        }
    }
    public void saveReport(UUID id, String owner, String report) throws Exception {
        update("UPDATE jobs SET report=?::jsonb,updated_at=now() WHERE id=? AND run_owner=? AND lease_until>now() AND state='running'", id, owner, report);
    }
    public void beginPublication(UUID id, String owner) throws Exception {
        update("UPDATE jobs SET state='publishing',publication_state=CASE WHEN publication_state='none' THEN 'uncertain' ELSE publication_state END,updated_at=now() WHERE id=? AND run_owner=? AND lease_until>now()", id, owner, null);
    }
    public void clearRejectedPublication(UUID id, String owner) throws Exception {
        update("UPDATE jobs SET publication_state='none' WHERE id=? AND run_owner=? AND lease_until>now()", id, owner, null);
    }
    public void finish(UUID id, String owner, String state, String failure, Long publicationId) throws Exception {
        if (!java.util.Set.of("succeeded", "partial", "failed", "superseded", "publication_uncertain", "analyzed").contains(state)) throw new IllegalArgumentException("Invalid job state");
        try (var connection = connection(); var statement = connection.prepareStatement("""
                UPDATE jobs SET state=?,failure_code=?,publication_id=COALESCE(?,publication_id),
                publication_state=CASE WHEN ?::bigint IS NULL THEN publication_state ELSE 'published' END,
                lease_until=NULL,updated_at=now() WHERE id=? AND run_owner=? AND lease_until>now()
                """)) {
            statement.setString(1, state); statement.setString(2, failure); statement.setObject(3, publicationId);
            statement.setObject(4, publicationId); statement.setObject(5, id); statement.setString(6, owner);
            if (statement.executeUpdate() != 1) throw new IllegalStateException("Job lease is no longer owned");
        }
    }
    public Job find(UUID id) throws Exception {
        try (var connection = connection(); var statement = connection.prepareStatement("SELECT * FROM jobs WHERE id=?")) {
            statement.setObject(1, id); try (var rows = statement.executeQuery()) { return rows.next() ? read(rows) : null; }
        }
    }
    @FunctionalInterface public interface Operation<T> { T run() throws Exception; }
    public <T> T publicationLock(Job job, Operation<T> operation) throws Exception {
        try (var connection = connection(); var statement = connection.prepareStatement("SELECT pg_advisory_lock(?)")) {
            long key = resourceLock(job.repositoryId(), job.number()); statement.setLong(1, key); statement.execute();
            try { return operation.run(); }
            finally { try (var unlock = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) { unlock.setLong(1, key); unlock.execute(); } }
        }
    }
    private void update(String sql, UUID id, String owner, String payload) throws Exception {
        try (var connection = connection(); var statement = connection.prepareStatement(sql)) {
            int index = 1; if (payload != null) statement.setString(index++, payload);
            statement.setObject(index++, id); statement.setString(index, owner);
            if (statement.executeUpdate() != 1) throw new IllegalStateException("Job lease is no longer owned");
        }
    }
    private static long resourceLock(long repositoryId, int number) { return (repositoryId * 31) ^ number; }
    private static Job read(ResultSet rows) throws Exception {
        return new Job((UUID) rows.getObject("id"), rows.getLong("repository_id"), rows.getString("repository"),
                rows.getInt("pr_number"), rows.getLong("installation_id"), rows.getString("base_sha").strip(), rows.getString("head_sha").strip(),
                rows.getString("base_branch"), rows.getString("state"), rows.getInt("attempts"), rows.getString("report"),
                rows.getString("publication_state"), (Long) rows.getObject("publication_id"));
    }
}
