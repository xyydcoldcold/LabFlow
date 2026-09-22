package com.labflow.backend.job;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import com.labflow.backend.project.ProjectPermissionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional(readOnly = true)
public class JobQueryService {

    private final JdbcTemplate jdbcTemplate;
    private final ProjectPermissionService permissionService;
    private final ObjectMapper objectMapper;

    public JobQueryService(
            JdbcTemplate jdbcTemplate,
            ProjectPermissionService permissionService,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.permissionService = permissionService;
        this.objectMapper = objectMapper;
    }

    public List<JobSummaryResponse> list(long projectId, long userId) {
        permissionService.requireView(projectId, userId);
        return jdbcTemplate.query("""
                SELECT id, project_id, molecular_input_id, experiment_config_id,
                       status, created_at, updated_at
                FROM jobs
                WHERE project_id = ?
                ORDER BY created_at DESC, id DESC
                """, (row, rowNumber) -> new JobSummaryResponse(
                row.getLong("id"), row.getLong("project_id"),
                row.getLong("molecular_input_id"), row.getLong("experiment_config_id"),
                JobState.valueOf(row.getString("status")),
                row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant()
        ), projectId);
    }

    public JobDetailsResponse get(long jobId, long userId) {
        List<JobRow> jobs = jdbcTemplate.query("""
                SELECT id, project_id, molecular_input_id, experiment_config_id,
                       status, spec_snapshot::text AS spec_snapshot, created_at, updated_at
                FROM jobs WHERE id = ?
                """, (row, rowNumber) -> new JobRow(
                row.getLong("id"), row.getLong("project_id"),
                row.getLong("molecular_input_id"), row.getLong("experiment_config_id"),
                JobState.valueOf(row.getString("status")), readJson(row.getString("spec_snapshot")),
                row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant()
        ), jobId);
        if (jobs.isEmpty()) {
            throw new JobNotFoundException(jobId);
        }
        JobRow job = jobs.getFirst();
        permissionService.requireView(job.projectId(), userId);

        List<JobAttemptResponse> attempts = jdbcTemplate.query("""
                SELECT a.id, a.attempt_no, a.status, a.worker_id, w.instance_name,
                       a.started_at, a.finished_at, a.failure_json::text AS failure
                FROM job_attempts a
                JOIN workers w ON w.id = a.worker_id
                WHERE a.job_id = ?
                ORDER BY a.attempt_no
                """, (row, rowNumber) -> new JobAttemptResponse(
                row.getLong("id"), row.getInt("attempt_no"), row.getString("status"),
                row.getLong("worker_id"), row.getString("instance_name"),
                row.getTimestamp("started_at").toInstant(), nullableInstant(row, "finished_at"),
                nullableJson(row.getString("failure"))
        ), jobId);
        List<JobLogChunkResponse> logs = jdbcTemplate.query("""
                SELECT id, attempt_id, seq_no, stream, emitted_at, content
                FROM job_log_chunks WHERE job_id = ? ORDER BY id
                """, (row, rowNumber) -> new JobLogChunkResponse(
                row.getLong("id"), row.getLong("attempt_id"), row.getLong("seq_no"),
                row.getString("stream"), row.getTimestamp("emitted_at").toInstant(),
                row.getString("content")
        ), jobId);
        List<JobResultResponse> results = jdbcTemplate.query("""
                SELECT attempt_id, summary_json::text AS summary, manifest_json::text AS manifest,
                       completed_at
                FROM job_results WHERE job_id = ?
                """, (row, rowNumber) -> new JobResultResponse(
                row.getLong("attempt_id"), readJson(row.getString("summary")),
                readJson(row.getString("manifest")), row.getTimestamp("completed_at").toInstant()
        ), jobId);
        return new JobDetailsResponse(
                job.id(), job.projectId(), job.molecularInputId(), job.experimentConfigId(),
                job.status(), job.specSnapshot(), job.createdAt(), job.updatedAt(),
                attempts, logs, results.isEmpty() ? null : results.getFirst()
        );
    }

    private java.time.Instant nullableInstant(ResultSet row, String column) throws SQLException {
        var value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private JsonNode nullableJson(String value) {
        return value == null ? null : readJson(value);
    }

    private JsonNode readJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Stored job JSON is invalid", exception);
        }
    }

    private record JobRow(
            long id,
            long projectId,
            long molecularInputId,
            long experimentConfigId,
            JobState status,
            JsonNode specSnapshot,
            java.time.Instant createdAt,
            java.time.Instant updatedAt
    ) {
    }
}
