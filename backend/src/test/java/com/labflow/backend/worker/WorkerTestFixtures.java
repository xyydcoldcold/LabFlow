package com.labflow.backend.worker;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

final class WorkerTestFixtures {
    private static final String INPUT_HASH = "a".repeat(64);

    static Attempt createAttempt(JdbcTemplate jdbc, Instant now, int attemptNo, int maxAttempts, Instant leaseExpiresAt) {
        String suffix = UUID.randomUUID().toString();
        long userId = jdbc.queryForObject("""
                INSERT INTO app_users (email, password_hash, display_name)
                VALUES (?, 'test-hash', 'Recovery Test') RETURNING id
                """, Long.class, suffix + "@example.com");
        long projectId = jdbc.queryForObject("INSERT INTO projects (name, owner_id) VALUES ('Recovery', ?) RETURNING id",
                Long.class, userId);
        String artifactPath = suffix + ".xyz";
        long inputId = jdbc.queryForObject("""
                INSERT INTO molecular_inputs (project_id, original_filename, sha256, artifact_path, size_bytes)
                VALUES (?, 'input.xyz', ?, ?, 1) RETURNING id
                """, Long.class, projectId, INPUT_HASH, artifactPath);
        long configId = jdbc.queryForObject("""
                INSERT INTO experiment_configs (project_id, name, version, spec_json, created_by)
                VALUES (?, 'Demo', 1, '{"taskType":"demo.sleep_hash"}'::jsonb, ?) RETURNING id
                """, Long.class, projectId, userId);
        long workerId = jdbc.queryForObject("""
                INSERT INTO workers (instance_name, image_digest, capabilities)
                VALUES (?, 'test-image', '["demo.sleep_hash"]'::jsonb) RETURNING id
                """, Long.class, suffix);
        long jobId = jdbc.queryForObject("""
                INSERT INTO jobs (project_id, molecular_input_id, experiment_config_id, submitted_by,
                                  status, spec_snapshot, max_attempts, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'RUNNING', jsonb_build_object(
                    'molecularInput', jsonb_build_object('artifactPath', ?::text, 'sha256', ?::text),
                    'experimentConfig', jsonb_build_object('spec', jsonb_build_object(
                        'taskType', 'demo.sleep_hash', 'sleepSeconds', 0,
                        'timeoutSeconds', 300, 'maxMemoryMb', 1024
                    ))
                ), ?, ?, ?) RETURNING id
                """, Long.class, projectId, inputId, configId, userId, artifactPath, INPUT_HASH,
                maxAttempts, Timestamp.from(now.minusSeconds(60)), Timestamp.from(now.minusSeconds(60)));
        String token = UUID.randomUUID().toString();
        long attemptId = jdbc.queryForObject("""
                INSERT INTO job_attempts (job_id, attempt_no, attempt_token, worker_id, status,
                                          lease_expires_at, started_at)
                VALUES (?, ?, CAST(? AS uuid), ?, 'ACTIVE', ?, ?) RETURNING id
                """, Long.class, jobId, attemptNo, token, workerId, Timestamp.from(leaseExpiresAt),
                Timestamp.from(now.minusSeconds(60)));
        return new Attempt(jobId, attemptId, workerId, token, userId, projectId);
    }

    record Attempt(long jobId, long attemptId, long workerId, String token, long userId, long projectId) {
    }
}
