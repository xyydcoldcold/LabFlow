CREATE TABLE workers (
    id BIGSERIAL PRIMARY KEY,
    instance_name VARCHAR(200) NOT NULL,
    image_digest VARCHAR(255) NOT NULL,
    capabilities JSONB NOT NULL,
    registered_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_heartbeat_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_workers_instance_name UNIQUE (instance_name),
    CONSTRAINT chk_workers_instance_name CHECK (length(btrim(instance_name)) > 0),
    CONSTRAINT chk_workers_image_digest CHECK (length(btrim(image_digest)) > 0),
    CONSTRAINT chk_workers_capabilities_array CHECK (jsonb_typeof(capabilities) = 'array')
);

CREATE INDEX idx_workers_last_heartbeat_at ON workers (last_heartbeat_at);

CREATE TABLE job_attempts (
    id BIGSERIAL PRIMARY KEY,
    job_id BIGINT NOT NULL,
    attempt_no INTEGER NOT NULL,
    attempt_token UUID NOT NULL,
    worker_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    lease_expires_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMPTZ,
    failure_json JSONB,
    CONSTRAINT fk_job_attempts_job
        FOREIGN KEY (job_id) REFERENCES jobs (id) ON DELETE CASCADE,
    CONSTRAINT fk_job_attempts_worker
        FOREIGN KEY (worker_id) REFERENCES workers (id) ON DELETE RESTRICT,
    CONSTRAINT uq_job_attempts_job_number UNIQUE (job_id, attempt_no),
    CONSTRAINT uq_job_attempts_token UNIQUE (attempt_token),
    CONSTRAINT chk_job_attempts_number CHECK (attempt_no >= 1),
    CONSTRAINT chk_job_attempts_status
        CHECK (status IN ('ACTIVE', 'SUCCEEDED', 'FAILED', 'LOST', 'CANCELLED')),
    CONSTRAINT chk_job_attempts_lease CHECK (lease_expires_at >= started_at),
    CONSTRAINT chk_job_attempts_finish CHECK (finished_at IS NULL OR finished_at >= started_at),
    CONSTRAINT chk_job_attempts_failure_object
        CHECK (failure_json IS NULL OR jsonb_typeof(failure_json) = 'object')
);

CREATE UNIQUE INDEX uq_job_attempts_active_job
    ON job_attempts (job_id)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_job_attempts_job_started_at
    ON job_attempts (job_id, started_at DESC);

CREATE INDEX idx_job_attempts_lease
    ON job_attempts (lease_expires_at)
    WHERE status = 'ACTIVE';

CREATE TABLE job_log_chunks (
    id BIGSERIAL PRIMARY KEY,
    job_id BIGINT NOT NULL,
    attempt_id BIGINT NOT NULL,
    seq_no BIGINT NOT NULL,
    stream VARCHAR(10) NOT NULL,
    emitted_at TIMESTAMPTZ NOT NULL,
    content TEXT NOT NULL,
    CONSTRAINT fk_job_log_chunks_job
        FOREIGN KEY (job_id) REFERENCES jobs (id) ON DELETE CASCADE,
    CONSTRAINT fk_job_log_chunks_attempt
        FOREIGN KEY (attempt_id) REFERENCES job_attempts (id) ON DELETE CASCADE,
    CONSTRAINT uq_job_log_chunks_attempt_seq UNIQUE (attempt_id, seq_no),
    CONSTRAINT chk_job_log_chunks_seq CHECK (seq_no >= 0),
    CONSTRAINT chk_job_log_chunks_stream CHECK (stream IN ('STDOUT', 'STDERR', 'SYSTEM')),
    CONSTRAINT chk_job_log_chunks_content CHECK (length(content) BETWEEN 1 AND 16384)
);

CREATE INDEX idx_job_log_chunks_job_id
    ON job_log_chunks (job_id, id);

CREATE TABLE job_results (
    job_id BIGINT PRIMARY KEY,
    attempt_id BIGINT NOT NULL,
    summary_json JSONB NOT NULL,
    manifest_json JSONB NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_job_results_job
        FOREIGN KEY (job_id) REFERENCES jobs (id) ON DELETE CASCADE,
    CONSTRAINT fk_job_results_attempt
        FOREIGN KEY (attempt_id) REFERENCES job_attempts (id) ON DELETE RESTRICT,
    CONSTRAINT uq_job_results_attempt UNIQUE (attempt_id),
    CONSTRAINT chk_job_results_summary_object CHECK (jsonb_typeof(summary_json) = 'object'),
    CONSTRAINT chk_job_results_manifest_object CHECK (jsonb_typeof(manifest_json) = 'object')
);
