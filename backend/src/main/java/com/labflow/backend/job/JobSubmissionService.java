package com.labflow.backend.job;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import com.labflow.backend.project.ProjectNotFoundException;
import com.labflow.backend.project.ProjectPermissionService;
import com.labflow.backend.project.ProjectRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JobSubmissionService {

    private final ProjectPermissionService permissionService;
    private final ProjectRepository projectRepository;
    private final JdbcTemplate jdbcTemplate;
    private final JobRequestHasher requestHasher;
    private final Clock clock;

    public JobSubmissionService(
            ProjectPermissionService permissionService,
            ProjectRepository projectRepository,
            JdbcTemplate jdbcTemplate,
            JobRequestHasher requestHasher,
            Clock clock
    ) {
        this.permissionService = permissionService;
        this.projectRepository = projectRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.requestHasher = requestHasher;
        this.clock = clock;
    }

    @Transactional
    public JobResponse submit(long userId, String idempotencyKey, CreateJobRequest request) {
        String key = validateKey(idempotencyKey);
        long projectId = request.projectId();
        permissionService.requireContribute(projectId, userId);

        // Serialize submissions within a project. A retry waits for the first transaction
        // to commit, then observes its idempotency record instead of racing its insert.
        projectRepository.findByIdForUpdate(projectId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId));

        String scope = "project:" + projectId + ":user:" + userId;
        String requestHash = requestHasher.hash(request);
        List<ExistingSubmission> existing = jdbcTemplate.query("""
                SELECT request_hash, job_id,
                       (response_json ->> 'projectId')::bigint AS project_id,
                       (response_json ->> 'molecularInputId')::bigint AS molecular_input_id,
                       (response_json ->> 'experimentConfigId')::bigint AS experiment_config_id,
                       response_json ->> 'status' AS status,
                       (response_json ->> 'createdAt')::timestamptz AS created_at
                FROM idempotency_records
                WHERE scope = ? AND idempotency_key = ?
                """, (row, number) -> new ExistingSubmission(
                row.getString("request_hash"),
                new JobResponse(
                        row.getLong("job_id"),
                        row.getLong("project_id"),
                        row.getLong("molecular_input_id"),
                        row.getLong("experiment_config_id"),
                        JobState.valueOf(row.getString("status")),
                        row.getTimestamp("created_at").toInstant()
                )
        ), scope, key);

        if (!existing.isEmpty()) {
            ExistingSubmission prior = existing.getFirst();
            if (!prior.requestHash().equals(requestHash)) {
                throw new IdempotencyKeyReusedException();
            }
            return prior.job();
        }

        Instant now = clock.instant();
        List<JobResponse> created = jdbcTemplate.query("""
                INSERT INTO jobs (
                    project_id, molecular_input_id, experiment_config_id, submitted_by,
                    status, spec_snapshot, created_at, updated_at
                )
                SELECT ?, input.id, config.id, ?, 'QUEUED',
                       jsonb_build_object(
                           'molecularInput', jsonb_build_object(
                               'id', input.id,
                               'originalFilename', input.original_filename,
                               'sha256', input.sha256,
                               'sizeBytes', input.size_bytes,
                               'artifactPath', input.artifact_path
                           ),
                           'experimentConfig', jsonb_build_object(
                               'id', config.id,
                               'name', config.name,
                               'version', config.version,
                               'spec', config.spec_json
                           )
                       ), ?, ?
                FROM molecular_inputs input
                CROSS JOIN experiment_configs config
                WHERE input.id = ? AND input.project_id = ?
                  AND config.id = ? AND config.project_id = ?
                RETURNING id, created_at
                """, (row, number) -> new JobResponse(
                row.getLong("id"), projectId, request.molecularInputId(),
                request.experimentConfigId(), JobState.QUEUED,
                row.getTimestamp("created_at").toInstant()
        ), projectId, userId, Timestamp.from(now), Timestamp.from(now),
                request.molecularInputId(), projectId, request.experimentConfigId(), projectId);

        if (created.isEmpty()) {
            throw new JobResourceNotFoundException();
        }

        JobResponse job = created.getFirst();
        jdbcTemplate.update("""
                INSERT INTO idempotency_records (
                    scope, idempotency_key, request_hash, job_id, response_json, created_at
                ) VALUES (?, ?, ?, ?, jsonb_build_object(
                    'id', ?::bigint,
                    'projectId', ?::bigint,
                    'molecularInputId', ?::bigint,
                    'experimentConfigId', ?::bigint,
                    'status', 'QUEUED',
                    'createdAt', ?::text
                ), ?)
                """, scope, key, requestHash, job.id(), job.id(), projectId,
                request.molecularInputId(), request.experimentConfigId(),
                job.createdAt().toString(), Timestamp.from(now));
        Long outboxEventId = jdbcTemplate.queryForObject("""
                INSERT INTO outbox_events (aggregate_id, event_type, payload, created_at, available_at)
                VALUES (?, 'JOB_QUEUED', '{}'::jsonb, ?, ?)
                RETURNING id
                """, Long.class, job.id(), Timestamp.from(now), Timestamp.from(now));
        jdbcTemplate.update("""
                UPDATE outbox_events
                SET payload = jsonb_build_object(
                    'jobId', ?::bigint, 'eventId', ?::bigint, 'schemaVersion', 1
                )
                WHERE id = ?
                """, job.id(), outboxEventId, outboxEventId);
        jdbcTemplate.update("""
                INSERT INTO job_events (job_id, from_status, to_status, event_type, created_at)
                VALUES (?, NULL, 'QUEUED', 'JOB_CREATED', ?)
                """, job.id(), Timestamp.from(now));
        return job;
    }

    private String validateKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()
                || idempotencyKey.length() > 255 || !idempotencyKey.equals(idempotencyKey.trim())) {
            throw new IllegalArgumentException("Idempotency-Key must be 1 to 255 characters without surrounding whitespace");
        }
        return idempotencyKey;
    }

    private record ExistingSubmission(String requestHash, JobResponse job) {
    }
}
