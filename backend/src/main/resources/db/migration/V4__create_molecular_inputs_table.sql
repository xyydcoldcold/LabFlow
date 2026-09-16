CREATE TABLE molecular_inputs (
    id BIGSERIAL PRIMARY KEY,
    project_id BIGINT NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    artifact_path VARCHAR(255) NOT NULL,
    size_bytes BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_molecular_inputs_project
        FOREIGN KEY (project_id)
        REFERENCES projects (id)
        ON DELETE CASCADE,
    CONSTRAINT uq_molecular_inputs_project_sha256
        UNIQUE (project_id, sha256),
    CONSTRAINT uq_molecular_inputs_artifact_path
        UNIQUE (artifact_path),
    CONSTRAINT chk_molecular_inputs_sha256
        CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_molecular_inputs_size
        CHECK (size_bytes >= 0)
);

CREATE INDEX idx_molecular_inputs_project_created_at
    ON molecular_inputs (project_id, created_at DESC);
