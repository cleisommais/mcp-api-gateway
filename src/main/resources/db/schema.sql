CREATE TABLE IF NOT EXISTS jobs (
 id uuid PRIMARY KEY,
 repository_id bigint NOT NULL,
 repository text NOT NULL,
 pr_number integer NOT NULL,
 installation_id bigint NOT NULL,
 base_sha char(40) NOT NULL,
 head_sha char(40) NOT NULL,
 base_branch text NOT NULL,
 source_updated_at timestamptz NOT NULL,
 state text NOT NULL DEFAULT 'queued',
 attempts integer NOT NULL DEFAULT 0,
 run_owner text,
 lease_until timestamptz,
 report jsonb,
 failure_code text,
 publication_state text NOT NULL DEFAULT 'none',
 publication_id bigint,
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(repository_id, pr_number, base_sha, head_sha)
);
CREATE INDEX IF NOT EXISTS jobs_pending ON jobs(state, created_at);
CREATE TABLE IF NOT EXISTS deliveries (
 delivery_id uuid PRIMARY KEY,
 payload_hash char(64) NOT NULL,
 event text NOT NULL,
 action text NOT NULL,
 outcome text NOT NULL,
 job_id uuid REFERENCES jobs(id),
 received_at timestamptz NOT NULL DEFAULT now()
);
