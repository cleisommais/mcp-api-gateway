package com.prreview.persistence.repository;

import com.prreview.common.config.SecretReader;
import com.prreview.domain.model.EnqueueResult;
import com.prreview.domain.model.ReviewEvent;
import com.prreview.domain.model.ReviewJob;
import com.prreview.domain.model.WebhookRequest;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.UUID;

/** PostgreSQL is both the durable record and the small POC work queue. */
public final class PostgresJobRepository {

    private final String url, user, password, schema;

    public PostgresJobRepository(String url, String user, String password, String schema)
            throws Exception {
        if (!schema.matches("[a-z][a-z0-9_]{0,62}"))
            throw new IllegalArgumentException("Invalid database schema");
        this.url = url;
        this.user = user;
        this.password = password;
        this.schema = schema;
        try (var connection = DriverManager.getConnection(url, user, password);
                var statement = connection.createStatement();
                var input = PostgresJobRepository.class.getResourceAsStream("/db/schema.sql")) {
            if (input == null) throw new IllegalStateException("Missing database schema resource");
            // API gateway and worker may initialize the same database concurrently.
            long migrationLock = 0x4d49470000000000L ^ Integer.toUnsignedLong(schema.hashCode());
            statement.execute("SELECT pg_advisory_lock(" + migrationLock + ")");
            try {
                statement.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
                statement.execute("SET search_path TO " + schema);
                statement.execute(
                        new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            } finally {
                statement.execute("SELECT pg_advisory_unlock(" + migrationLock + ")");
            }
        }
    }

    Connection connection() throws Exception {
        var connection = DriverManager.getConnection(url, user, password);
        try (var statement = connection.createStatement()) {
            statement.execute("SET search_path TO " + schema);
        }
        return connection;
    }

    public static PostgresJobRepository environment() throws Exception {
        return new PostgresJobRepository(
                System.getenv()
                        .getOrDefault("DATABASE_URL", "jdbc:postgresql://postgres:5432/mcp_review"),
                System.getenv().getOrDefault("DATABASE_USER", "mcp_review"),
                SecretReader.required("DATABASE_PASSWORD"),
                System.getenv().getOrDefault("DATABASE_SCHEMA", "review"));
    }

    public EnqueueResult enqueue(ReviewEvent event) throws Exception {
        try (var connection = connection()) {
            connection.setAutoCommit(false);
            try {
                // Serialize delivery/key decisions without storing raw webhook bodies.
                try (var lock = connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                    lock.setLong(
                            1,
                            resourceLock(event.platform(), event.repositoryId(), event.number())
                                    ^ Long.MIN_VALUE);
                    lock.execute();
                }
                try (var existing =
                        connection.prepareStatement(
                                "SELECT payload_hash,job_id,outcome FROM deliveries WHERE"
                                        + " delivery_id=?")) {
                    existing.setObject(1, UUID.fromString(event.deliveryId()));
                    try (var rows = existing.executeQuery()) {
                        if (rows.next()) {
                            if (!rows.getString(1).equals(event.payloadHash()))
                                throw new SecurityException(
                                        "Delivery ID reused with a different body");
                            var result =
                                    new EnqueueResult(
                                            (UUID) rows.getObject(2), true, rows.getString(3));
                            connection.commit();
                            return result;
                        }
                    }
                }
                UUID jobId = null;
                boolean duplicateSnapshot = false;
                if (event.eligible()) {
                    jobId =
                            event.event().equals("manual_review")
                                    ? UUID.fromString(event.deliveryId())
                                    : UUID.randomUUID();
                    UUID proposed = jobId;
                    try (var insert =
                            connection.prepareStatement(
                                    """
INSERT INTO jobs(id,repository_id,repository,pr_number,installation_id,base_sha,head_sha,base_branch,source_updated_at,platform)
VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(platform,repository_id,pr_number,base_sha,head_sha)
DO UPDATE SET repository=EXCLUDED.repository RETURNING id
""")) {
                        insert.setObject(1, jobId);
                        insert.setLong(2, event.repositoryId());
                        insert.setString(3, event.repository());
                        insert.setInt(4, event.number());
                        insert.setLong(5, event.installationId());
                        insert.setString(6, event.baseSha());
                        insert.setString(7, event.headSha());
                        insert.setString(8, event.baseBranch());
                        insert.setObject(
                                9,
                                java.time.OffsetDateTime.ofInstant(
                                        event.updatedAt(), java.time.ZoneOffset.UTC));
                        insert.setString(10, event.platform());
                        try (var rows = insert.executeQuery()) {
                            rows.next();
                            jobId = (UUID) rows.getObject(1);
                            duplicateSnapshot = !jobId.equals(proposed);
                        }
                    }
                    try (var supersede =
                            connection.prepareStatement(
                                    """
UPDATE jobs SET state='superseded',updated_at=now() WHERE repository_id=? AND pr_number=?
AND state='queued' AND id<>? AND source_updated_at<? AND platform=?
""")) {
                        supersede.setLong(1, event.repositoryId());
                        supersede.setInt(2, event.number());
                        supersede.setObject(3, jobId);
                        supersede.setObject(
                                4,
                                java.time.OffsetDateTime.ofInstant(
                                        event.updatedAt(), java.time.ZoneOffset.UTC));
                        supersede.setString(5, event.platform());
                        supersede.executeUpdate();
                    }
                }
                try (var delivery =
                        connection.prepareStatement(
                                "INSERT INTO"
                                    + " deliveries(delivery_id,payload_hash,event,action,outcome,job_id)"
                                    + " VALUES(?,?,?,?,?,?)")) {
                    delivery.setObject(1, UUID.fromString(event.deliveryId()));
                    delivery.setString(2, event.payloadHash());
                    delivery.setString(3, event.event());
                    delivery.setString(4, event.action());
                    delivery.setString(5, event.reason());
                    delivery.setObject(6, jobId);
                    delivery.executeUpdate();
                }
                connection.commit();
                return new EnqueueResult(jobId, duplicateSnapshot, event.reason());
            } catch (Exception error) {
                connection.rollback();
                throw error;
            }
        }
    }

    public boolean acceptWebhook(WebhookRequest request) throws Exception {
        try (var connection = connection();
                var insert =
                        connection.prepareStatement(
                                """
INSERT INTO webhook_requests(id,platform,provider_delivery,repository,pr_number,source_branch,head_sha,event,action,payload_hash)
VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO NOTHING
""")) {
            insert.setObject(1, request.id());
            insert.setString(2, request.platform());
            insert.setString(3, request.delivery());
            insert.setString(4, request.repository());
            insert.setInt(5, request.number());
            insert.setString(6, request.branch());
            insert.setString(7, request.head());
            insert.setString(8, request.event());
            insert.setString(9, request.action());
            insert.setString(10, request.payloadHash());
            boolean added = insert.executeUpdate() == 1;
            if (!added)
                try (var query =
                        connection.prepareStatement(
                                "SELECT payload_hash FROM webhook_requests WHERE id=?")) {
                    query.setObject(1, request.id());
                    try (var rows = query.executeQuery()) {
                        if (!rows.next() || !rows.getString(1).equals(request.payloadHash()))
                            throw new SecurityException("Delivery ID reused with a different body");
                    }
                }
            return added;
        }
    }

    public WebhookRequest claimWebhook() throws Exception {
        try (var connection = connection();
                var claim =
                        connection.prepareStatement(
                                """
WITH candidate AS (
 SELECT id FROM webhook_requests WHERE (state='queued' AND next_attempt_at<=now())
 OR (state='processing' AND lease_until<now())
 ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1)
UPDATE webhook_requests SET state='processing',attempts=attempts+1,lease_until=now()+interval '5 minutes',updated_at=now()
FROM candidate WHERE webhook_requests.id=candidate.id RETURNING webhook_requests.*
""")) {
            try (var rows = claim.executeQuery()) {
                if (!rows.next()) return null;
                return new WebhookRequest(
                        (UUID) rows.getObject("id"),
                        rows.getString("platform"),
                        rows.getString("provider_delivery"),
                        rows.getString("repository"),
                        rows.getInt("pr_number"),
                        rows.getString("source_branch"),
                        rows.getString("head_sha"),
                        rows.getString("event"),
                        rows.getString("action"),
                        rows.getString("payload_hash"));
            }
        }
    }

    public void finishWebhook(UUID id, String failure) throws Exception {
        try (var connection = connection();
                var update =
                        connection.prepareStatement(
                                """
UPDATE webhook_requests SET state=CASE WHEN ?::text IS NULL THEN 'complete' WHEN attempts>=12 THEN 'failed' ELSE 'queued' END,
failure_code=?,lease_until=NULL,next_attempt_at=now()+interval '20 seconds',updated_at=now() WHERE id=?
""")) {
            update.setString(1, failure);
            update.setString(2, failure);
            update.setObject(3, id);
            update.executeUpdate();
        }
    }

    public ReviewJob claim(String owner, int leaseSeconds) throws Exception {
        return claim(owner, leaseSeconds, Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    public ReviewJob claim(String owner, int leaseSeconds, int maxActive, int perRepository)
            throws Exception {
        if (leaseSeconds < 1 || maxActive < 1 || perRepository < 1)
            throw new IllegalArgumentException("Invalid job claim limits");
        try (var connection = connection()) {
            connection.setAutoCommit(false);
            // Count and claim atomically in this schema; network/model work is outside this
            // transaction.
            try (var lock = connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                lock.setLong(1, 0x4637430000000000L ^ Integer.toUnsignedLong(schema.hashCode()));
                lock.execute();
            }
            try (var statement =
                    connection.prepareStatement(
                            """
WITH candidate AS (
  SELECT q.id FROM jobs q WHERE q.state='queued'
    AND (SELECT count(*) FROM jobs a WHERE a.state IN ('running','publishing') AND a.lease_until>now()) < ?
    AND (SELECT count(*) FROM jobs r WHERE r.repository_id=q.repository_id AND r.platform=q.platform
         AND r.state IN ('running','publishing') AND r.lease_until>now()) < ?
  ORDER BY q.created_at FOR UPDATE OF q SKIP LOCKED LIMIT 1)
UPDATE jobs SET state='running',attempts=attempts+1,run_owner=?,lease_until=now()+(? * interval '1 second'),updated_at=now()
FROM candidate WHERE jobs.id=candidate.id RETURNING jobs.*
""")) {
                statement.setInt(1, maxActive);
                statement.setInt(2, perRepository);
                statement.setString(3, owner);
                statement.setInt(4, leaseSeconds);
                ReviewJob job;
                try (var rows = statement.executeQuery()) {
                    job = rows.next() ? read(rows) : null;
                }
                connection.commit();
                return job;
            }
        }
    }

    public int recoverExpired() throws Exception {
        try (var connection = connection();
                var statement = connection.createStatement()) {
            return statement.executeUpdate(
                    "UPDATE jobs SET"
                        + " state='queued',run_owner=NULL,lease_until=NULL,failure_code='lease-expired',updated_at=now()"
                        + " WHERE state IN ('running','publishing') AND lease_until<now()");
        }
    }

    public boolean heartbeat(UUID id, String owner, int leaseSeconds) throws Exception {
        try (var connection = connection();
                var statement =
                        connection.prepareStatement(
                                "UPDATE jobs SET lease_until=now()+(? * interval '1 second') WHERE"
                                        + " id=? AND run_owner=? AND lease_until>now() AND state IN"
                                        + " ('running','publishing')")) {
            statement.setInt(1, leaseSeconds);
            statement.setObject(2, id);
            statement.setString(3, owner);
            return statement.executeUpdate() == 1;
        }
    }

    public void saveReport(UUID id, String owner, String report) throws Exception {
        update(
                "UPDATE jobs SET report=?::jsonb,updated_at=now() WHERE id=? AND run_owner=? AND"
                        + " lease_until>now() AND state='running'",
                id,
                owner,
                report);
    }

    public void beginPublication(UUID id, String owner) throws Exception {
        update(
                "UPDATE jobs SET state='publishing',publication_state=CASE WHEN"
                        + " publication_state='none' THEN 'uncertain' ELSE publication_state"
                        + " END,updated_at=now() WHERE id=? AND run_owner=? AND lease_until>now()",
                id,
                owner,
                null);
    }

    public void clearRejectedPublication(UUID id, String owner) throws Exception {
        update(
                "UPDATE jobs SET publication_state='none' WHERE id=? AND run_owner=? AND"
                        + " lease_until>now()",
                id,
                owner,
                null);
    }

    public void finish(UUID id, String owner, String state, String failure, Long publicationId)
            throws Exception {
        if (!java.util.Set.of(
                        "succeeded", "partial", "failed", "superseded", "publication_uncertain")
                .contains(state)) throw new IllegalArgumentException("Invalid job state");
        try (var connection = connection();
                var statement =
                        connection.prepareStatement(
                                """
UPDATE jobs SET state=?,failure_code=?,publication_id=COALESCE(?,publication_id),
publication_state=CASE WHEN ?::bigint IS NULL THEN publication_state ELSE 'published' END,
lease_until=NULL,updated_at=now() WHERE id=? AND run_owner=? AND lease_until>now()
""")) {
            statement.setString(1, state);
            statement.setString(2, failure);
            statement.setObject(3, publicationId);
            statement.setObject(4, publicationId);
            statement.setObject(5, id);
            statement.setString(6, owner);
            if (statement.executeUpdate() != 1)
                throw new IllegalStateException("Job lease is no longer owned");
        }
    }

    public ReviewJob find(UUID id) throws Exception {
        try (var connection = connection();
                var statement = connection.prepareStatement("SELECT * FROM jobs WHERE id=?")) {
            statement.setObject(1, id);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? read(rows) : null;
            }
        }
    }

    public java.util.List<ReviewJob> list() throws Exception {
        var jobs = new java.util.ArrayList<ReviewJob>();
        try (var connection = connection();
                var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT * FROM jobs ORDER BY created_at,id")) {
            while (rows.next()) jobs.add(read(rows));
        }
        return jobs;
    }

    public int retry(UUID id) throws Exception {
        try (var connection = connection();
                var statement =
                        connection.prepareStatement(
                                """
UPDATE jobs SET state='queued',run_owner=NULL,lease_until=NULL,failure_code=NULL
WHERE id=? AND state IN ('failed','publication_uncertain')
""")) {
            statement.setObject(1, id);
            return statement.executeUpdate();
        }
    }

    /**
     * Explicit local administration: recompute a terminal job while preserving comment identity.
     */
    public int reanalyze(UUID id) throws Exception {
        try (var connection = connection();
                var statement =
                        connection.prepareStatement(
                                """
UPDATE jobs SET state='queued',report=NULL,run_owner=NULL,lease_until=NULL,
failure_code=NULL,updated_at=now() WHERE id=? AND state NOT IN ('queued','running','publishing')
""")) {
            statement.setObject(1, id);
            return statement.executeUpdate();
        }
    }

    @FunctionalInterface
    public interface Operation<T> {
        T run() throws Exception;
    }

    public <T> T publicationLock(ReviewJob job, Operation<T> operation) throws Exception {
        try (var connection = connection();
                var statement = connection.prepareStatement("SELECT pg_advisory_lock(?)")) {
            long key = resourceLock(job.platform(), job.repositoryId(), job.number());
            statement.setLong(1, key);
            statement.execute();
            try {
                return operation.run();
            } finally {
                try (var unlock = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                    unlock.setLong(1, key);
                    unlock.execute();
                }
            }
        }
    }

    private void update(String sql, UUID id, String owner, String payload) throws Exception {
        try (var connection = connection();
                var statement = connection.prepareStatement(sql)) {
            int index = 1;
            if (payload != null) statement.setString(index++, payload);
            statement.setObject(index++, id);
            statement.setString(index, owner);
            if (statement.executeUpdate() != 1)
                throw new IllegalStateException("Job lease is no longer owned");
        }
    }

    private static long resourceLock(String platform, long repositoryId, int number) {
        return (repositoryId * 31) ^ number ^ ((long) platform.hashCode() << 32);
    }

    private static ReviewJob read(ResultSet rows) throws Exception {
        return new ReviewJob(
                (UUID) rows.getObject("id"),
                rows.getString("platform"),
                rows.getLong("repository_id"),
                rows.getString("repository"),
                rows.getInt("pr_number"),
                rows.getLong("installation_id"),
                rows.getString("base_sha").strip(),
                rows.getString("head_sha").strip(),
                rows.getString("base_branch"),
                rows.getTimestamp("source_updated_at").toInstant(),
                rows.getString("state"),
                rows.getInt("attempts"),
                rows.getString("run_owner"),
                rows.getTimestamp("lease_until") == null
                        ? null
                        : rows.getTimestamp("lease_until").toInstant(),
                rows.getString("report"),
                rows.getString("failure_code"),
                rows.getString("publication_state"),
                (Long) rows.getObject("publication_id"),
                rows.getTimestamp("created_at").toInstant(),
                rows.getTimestamp("updated_at").toInstant());
    }
}
