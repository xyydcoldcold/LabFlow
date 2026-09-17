CREATE TABLE experiment_configs (
    id BIGSERIAL PRIMARY KEY,
    project_id BIGINT NOT NULL,
    name VARCHAR(200) NOT NULL,
    version INTEGER NOT NULL,
    spec_json JSONB NOT NULL,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_experiment_configs_project
        FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE,
    CONSTRAINT fk_experiment_configs_created_by
        FOREIGN KEY (created_by) REFERENCES app_users (id),
    CONSTRAINT uq_experiment_configs_project_name_version
        UNIQUE (project_id, name, version),
    CONSTRAINT chk_experiment_configs_name
        CHECK (length(btrim(name)) > 0),
    CONSTRAINT chk_experiment_configs_version
        CHECK (version >= 1),
    CONSTRAINT chk_experiment_configs_spec_object
        CHECK (jsonb_typeof(spec_json) = 'object')
);

CREATE INDEX idx_experiment_configs_project_created_at
    ON experiment_configs (project_id, created_at DESC);

CREATE INDEX idx_experiment_configs_project_name_version
    ON experiment_configs (project_id, name, version DESC);
