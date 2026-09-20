CREATE TABLE jobs (
    id BIGSERIAL PRIMARY KEY,
    project_id BIGINT NOT NULL,
    molecular_input_id BIGINT NOT NULL,
    experiment_config_id BIGINT NOT NULL,
    submitted_by BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    spec_snapshot JSONB NOT NULL,
    max_attempts INTEGER NOT NULL DEFAULT 3,
    version BIGINT NOT NULL DEFAULT 0,
    cancel_requested_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_jobs_project
        FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE,
    CONSTRAINT fk_jobs_molecular_input
        FOREIGN KEY (molecular_input_id) REFERENCES molecular_inputs (id) ON DELETE RESTRICT,
    CONSTRAINT fk_jobs_experiment_config
        FOREIGN KEY (experiment_config_id) REFERENCES experiment_configs (id) ON DELETE RESTRICT,
    CONSTRAINT fk_jobs_submitted_by
        FOREIGN KEY (submitted_by) REFERENCES app_users (id) ON DELETE RESTRICT,
    CONSTRAINT chk_jobs_status
        CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT chk_jobs_spec_snapshot_object
        CHECK (jsonb_typeof(spec_snapshot) = 'object'),
    CONSTRAINT chk_jobs_max_attempts
        CHECK (max_attempts >= 1),
    CONSTRAINT chk_jobs_version
        CHECK (version >= 0)
);

CREATE INDEX idx_jobs_project_created_at
    ON jobs (project_id, created_at DESC);

CREATE INDEX idx_jobs_status_created_at
    ON jobs (status, created_at);

CREATE TABLE idempotency_records (
    id BIGSERIAL PRIMARY KEY,
    scope VARCHAR(255) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    job_id BIGINT NOT NULL,
    response_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_idempotency_records_job
        FOREIGN KEY (job_id) REFERENCES jobs (id) ON DELETE CASCADE,
    CONSTRAINT uq_idempotency_records_scope_key
        UNIQUE (scope, idempotency_key),
    CONSTRAINT uq_idempotency_records_job
        UNIQUE (job_id),
    CONSTRAINT chk_idempotency_records_scope
        CHECK (length(btrim(scope)) > 0),
    CONSTRAINT chk_idempotency_records_key
        CHECK (length(btrim(idempotency_key)) > 0),
    CONSTRAINT chk_idempotency_records_request_hash
        CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_idempotency_records_response_object
        CHECK (jsonb_typeof(response_json) = 'object')
);

CREATE TABLE outbox_events (
    id BIGSERIAL PRIMARY KEY,
    aggregate_id BIGINT NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    publish_attempts INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    CONSTRAINT chk_outbox_events_type
        CHECK (length(btrim(event_type)) > 0),
    CONSTRAINT chk_outbox_events_payload_object
        CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT chk_outbox_events_publish_attempts
        CHECK (publish_attempts >= 0),
    CONSTRAINT chk_outbox_events_publication_time
        CHECK (published_at IS NULL OR published_at >= created_at)
);

CREATE INDEX idx_outbox_events_unpublished
    ON outbox_events (available_at, id)
    WHERE published_at IS NULL;

CREATE TABLE job_events (
    id BIGSERIAL PRIMARY KEY,
    job_id BIGINT NOT NULL,
    from_status VARCHAR(20),
    to_status VARCHAR(20) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_job_events_job
        FOREIGN KEY (job_id) REFERENCES jobs (id) ON DELETE CASCADE,
    CONSTRAINT chk_job_events_from_status
        CHECK (from_status IS NULL OR from_status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT chk_job_events_to_status
        CHECK (to_status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT chk_job_events_type
        CHECK (length(btrim(event_type)) > 0),
    CONSTRAINT chk_job_events_details_object
        CHECK (jsonb_typeof(details) = 'object'),
    CONSTRAINT chk_job_events_state_change
        CHECK (from_status IS NULL OR from_status <> to_status)
);

CREATE INDEX idx_job_events_job_created_at
    ON job_events (job_id, created_at, id);
