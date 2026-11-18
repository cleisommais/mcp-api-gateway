CREATE TABLE IF NOT EXISTS jobs (
 id uuid PRIMARY KEY,
 platform text NOT NULL DEFAULT 'github',
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
 updated_at timestamptz NOT NULL DEFAULT now()
);
-- Preserve the existing local queue when upgrading from the GitHub-only schema.
ALTER TABLE jobs ADD COLUMN IF NOT EXISTS platform text NOT NULL DEFAULT 'github';
ALTER TABLE jobs DROP CONSTRAINT IF EXISTS jobs_repository_id_pr_number_base_sha_head_sha_key;
CREATE UNIQUE INDEX IF NOT EXISTS jobs_snapshot_target_unique ON jobs(platform,repository_id,pr_number,base_sha,head_sha,base_branch);
DROP INDEX IF EXISTS jobs_snapshot_unique;
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
-- Authenticated webhook admission waits for provider consistency outside the HTTP callback.
-- Only normalized routing metadata is retained; raw payloads and authentication headers are not.
CREATE TABLE IF NOT EXISTS webhook_requests (
 id uuid PRIMARY KEY,
 platform text NOT NULL,
 provider_delivery text NOT NULL,
 repository text NOT NULL,
 pr_number integer NOT NULL,
 source_branch text NOT NULL,
 head_sha text NOT NULL,
 event text NOT NULL,
 action text NOT NULL,
 payload_hash char(64) NOT NULL,
 state text NOT NULL DEFAULT 'queued',
 attempts integer NOT NULL DEFAULT 0,
 next_attempt_at timestamptz NOT NULL DEFAULT now(),
 lease_until timestamptz,
 failure_code text,
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now()
);
