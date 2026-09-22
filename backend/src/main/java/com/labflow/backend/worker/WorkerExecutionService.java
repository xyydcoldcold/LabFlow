package com.labflow.backend.worker;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.labflow.backend.artifact.ArtifactStorageProperties;
import com.labflow.backend.job.JobLogChunkResponse;
import com.labflow.backend.job.JobLogStreamService;
import com.labflow.backend.job.JobNotFoundException;
import com.labflow.backend.job.JobState;
import com.labflow.backend.job.JobStateMachine;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;

@Service
public class WorkerExecutionService {

    private static final Set<String> TASK_TYPES = Set.of("demo.sleep_hash", "pyscf.single_point");
    private static final Set<String> LOG_STREAMS = Set.of("STDOUT", "STDERR", "SYSTEM");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final WorkerProperties properties;
    private final ArtifactStorageProperties artifactProperties;
    private final JobStateMachine stateMachine;
    private final JobLogStreamService logStreamService;
    private final Clock clock;

    public WorkerExecutionService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            WorkerProperties properties,
            ArtifactStorageProperties artifactProperties,
            JobStateMachine stateMachine,
            JobLogStreamService logStreamService,
            Clock clock
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.artifactProperties = artifactProperties;
        this.stateMachine = stateMachine;
        this.logStreamService = logStreamService;
        this.clock = clock;
    }

    @Transactional
    public WorkerRegistrationResponse register(RegisterWorkerRequest request) {
        String instanceName = request.instanceName().trim();
        String imageDigest = request.imageDigest().trim();
        List<String> capabilities = request.capabilities().stream()
                .map(String::trim)
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
        if (capabilities.stream().anyMatch(capability -> !TASK_TYPES.contains(capability))) {
            throw new IllegalArgumentException("Worker advertised an unsupported capability");
        }
        String capabilitiesJson = JsonNodeFactory.instance.arrayNode()
                .addAll(capabilities.stream().map(JsonNodeFactory.instance::textNode).toList())
                .toString();
        Instant now = clock.instant();
        return jdbcTemplate.queryForObject("""
                INSERT INTO workers (
                    instance_name, image_digest, capabilities, registered_at, last_heartbeat_at
                ) VALUES (?, ?, CAST(? AS jsonb), ?, ?)
                ON CONFLICT (instance_name) DO UPDATE SET
                    image_digest = EXCLUDED.image_digest,
                    capabilities = EXCLUDED.capabilities,
                    last_heartbeat_at = EXCLUDED.last_heartbeat_at
                RETURNING id, instance_name, image_digest, registered_at
                """, (row, rowNumber) -> new WorkerRegistrationResponse(
                row.getLong("id"),
                row.getString("instance_name"),
                row.getString("image_digest"),
                capabilities,
                row.getTimestamp("registered_at").toInstant()
        ), instanceName, imageDigest, capabilitiesJson, Timestamp.from(now), Timestamp.from(now));
    }

    @Transactional
    public ClaimJobResponse claim(long jobId, long workerId) {
        List<ClaimableJob> jobs = jdbcTemplate.query("""
                SELECT status, max_attempts, spec_snapshot::text AS snapshot,
                       spec_snapshot -> 'experimentConfig' -> 'spec' ->> 'taskType' AS task_type,
                       spec_snapshot -> 'molecularInput' ->> 'artifactPath' AS artifact_path,
                       spec_snapshot -> 'molecularInput' ->> 'sha256' AS input_sha256
                FROM jobs
                WHERE id = ?
                FOR UPDATE
                """, (row, rowNumber) -> new ClaimableJob(
                JobState.valueOf(row.getString("status")),
                row.getInt("max_attempts"),
                readJson(row.getString("snapshot")),
                row.getString("task_type"),
                row.getString("artifact_path"),
                row.getString("input_sha256")
        ), jobId);
        if (jobs.isEmpty()) {
            throw new JobNotFoundException(jobId);
        }
        ClaimableJob job = jobs.getFirst();
        if (job.state() != JobState.QUEUED) {
            throw new JobNotClaimableException(jobId);
        }

        Boolean capableWorker = jdbcTemplate.query("""
                SELECT capabilities @> CAST(? AS jsonb)
                FROM workers WHERE id = ?
                """, result -> result.next() ? result.getBoolean(1) : null,
                "[\"" + job.taskType() + "\"]", workerId);
        if (capableWorker == null) {
            throw new WorkerNotFoundException(workerId);
        }
        if (!capableWorker) {
            throw new IllegalArgumentException("Worker does not support task type " + job.taskType());
        }

        int attemptNo = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(attempt_no), 0) + 1 FROM job_attempts WHERE job_id = ?
                """, Integer.class, jobId);
        if (attemptNo > job.maxAttempts()) {
            throw new JobNotClaimableException(jobId);
        }

        Instant now = clock.instant();
        Instant leaseExpiresAt = now.plus(properties.attemptLease());
        String token = UUID.randomUUID().toString();
        Long attemptId = jdbcTemplate.queryForObject("""
                INSERT INTO job_attempts (
                    job_id, attempt_no, attempt_token, worker_id, status,
                    lease_expires_at, started_at
                ) VALUES (?, ?, CAST(? AS uuid), ?, 'ACTIVE', ?, ?)
                RETURNING id
                """, Long.class, jobId, attemptNo, token, workerId,
                Timestamp.from(leaseExpiresAt), Timestamp.from(now));
        stateMachine.requireTransition(JobState.QUEUED, JobState.RUNNING);
        jdbcTemplate.update("""
                UPDATE jobs SET status = 'RUNNING', version = version + 1, updated_at = ? WHERE id = ?
                """, Timestamp.from(now), jobId);
        jdbcTemplate.update("""
                INSERT INTO job_events (job_id, from_status, to_status, event_type, details, created_at)
                VALUES (?, 'QUEUED', 'RUNNING', 'JOB_CLAIMED',
                        jsonb_build_object('attemptId', ?::bigint, 'workerId', ?::bigint), ?)
                """, jobId, attemptId, workerId, Timestamp.from(now));

        JsonNode spec = job.snapshot().get("experimentConfig").get("spec").deepCopy();
        return new ClaimJobResponse(
                jobId,
                attemptId,
                attemptNo,
                token,
                leaseExpiresAt,
                job.taskType(),
                resolveArtifact(job.artifactPath()),
                job.inputSha256(),
                spec
        );
    }

    @Transactional
    public JobLogChunkResponse appendLog(long attemptId, String token, AppendLogRequest request) {
        LockedAttempt attempt = lockAttempt(attemptId);
        requireActiveToken(attempt, attemptId, token);
        String stream = request.stream().trim().toUpperCase();
        if (!LOG_STREAMS.contains(stream)) {
            throw new IllegalArgumentException("Log stream must be STDOUT, STDERR, or SYSTEM");
        }

        List<JobLogChunkResponse> existing = jdbcTemplate.query("""
                SELECT id, attempt_id, seq_no, stream, emitted_at, content
                FROM job_log_chunks WHERE attempt_id = ? AND seq_no = ?
                """, (row, rowNumber) -> mapLog(row), attemptId, request.seqNo());
        if (!existing.isEmpty()) {
            JobLogChunkResponse chunk = existing.getFirst();
            if (!chunk.stream().equals(stream) || !chunk.content().equals(request.content())) {
                throw new IllegalArgumentException("A different log chunk already uses this sequence number");
            }
            return chunk;
        }

        Long logId = jdbcTemplate.queryForObject("""
                INSERT INTO job_log_chunks (
                    job_id, attempt_id, seq_no, stream, emitted_at, content
                ) VALUES (?, ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, attempt.jobId(), attemptId, request.seqNo(), stream,
                Timestamp.from(request.emittedAt()), request.content());
        JobLogChunkResponse chunk = new JobLogChunkResponse(
                logId, attemptId, request.seqNo(), stream, request.emittedAt(), request.content()
        );
        broadcastAfterCommit(attempt.jobId(), chunk);
        return chunk;
    }

    @Transactional
    public AttemptCompletionResponse succeed(
            long attemptId,
            String token,
            CompleteAttemptRequest request
    ) {
        requireObject(request.summary(), "summary");
        requireObject(request.manifest(), "manifest");
        LockedAttempt attempt = lockAttempt(attemptId);
        requireToken(attempt, attemptId, token);
        if ("SUCCEEDED".equals(attempt.attemptStatus()) && attempt.jobState() == JobState.SUCCEEDED) {
            return new AttemptCompletionResponse(attempt.jobId(), attemptId, "SUCCEEDED", attempt.finishedAt());
        }
        requireActive(attempt, attemptId);

        Instant now = clock.instant();
        jdbcTemplate.update("""
                INSERT INTO job_results (job_id, attempt_id, summary_json, manifest_json, completed_at)
                VALUES (?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?)
                """, attempt.jobId(), attemptId, request.summary().toString(),
                request.manifest().toString(), Timestamp.from(now));
        finishAttemptAndJob(attempt, attemptId, JobState.SUCCEEDED, now, null);
        return new AttemptCompletionResponse(attempt.jobId(), attemptId, "SUCCEEDED", now);
    }

    @Transactional
    public AttemptCompletionResponse fail(long attemptId, String token, FailAttemptRequest request) {
        requireObject(request.error(), "error");
        LockedAttempt attempt = lockAttempt(attemptId);
        requireToken(attempt, attemptId, token);
        if ("FAILED".equals(attempt.attemptStatus()) && attempt.jobState() == JobState.FAILED) {
            return new AttemptCompletionResponse(attempt.jobId(), attemptId, "FAILED", attempt.finishedAt());
        }
        requireActive(attempt, attemptId);

        Instant now = clock.instant();
        finishAttemptAndJob(attempt, attemptId, JobState.FAILED, now, request.error());
        return new AttemptCompletionResponse(attempt.jobId(), attemptId, "FAILED", now);
    }

    private void finishAttemptAndJob(
            LockedAttempt attempt,
            long attemptId,
            JobState finalState,
            Instant now,
            JsonNode failure
    ) {
        stateMachine.requireTransition(JobState.RUNNING, finalState);
        jdbcTemplate.update("""
                UPDATE job_attempts
                SET status = ?, finished_at = ?, failure_json = CAST(? AS jsonb)
                WHERE id = ?
                """, finalState.name(), Timestamp.from(now), failure == null ? null : failure.toString(), attemptId);
        jdbcTemplate.update("""
                UPDATE jobs SET status = ?, version = version + 1, updated_at = ? WHERE id = ?
                """, finalState.name(), Timestamp.from(now), attempt.jobId());
        jdbcTemplate.update("""
                INSERT INTO job_events (job_id, from_status, to_status, event_type, details, created_at)
                VALUES (?, 'RUNNING', ?, ?, jsonb_build_object('attemptId', ?::bigint), ?)
                """, attempt.jobId(), finalState.name(), "JOB_" + finalState.name(),
                attemptId, Timestamp.from(now));
    }

    private LockedAttempt lockAttempt(long attemptId) {
        List<LockedAttempt> attempts = jdbcTemplate.query("""
                SELECT a.job_id, a.status AS attempt_status, a.attempt_token::text AS attempt_token,
                       a.finished_at, j.status AS job_status
                FROM job_attempts a
                JOIN jobs j ON j.id = a.job_id
                WHERE a.id = ?
                FOR UPDATE OF a, j
                """, (row, rowNumber) -> new LockedAttempt(
                row.getLong("job_id"),
                row.getString("attempt_status"),
                row.getString("attempt_token"),
                JobState.valueOf(row.getString("job_status")),
                row.getTimestamp("finished_at") == null ? null : row.getTimestamp("finished_at").toInstant()
        ), attemptId);
        if (attempts.isEmpty()) {
            throw new AttemptNotFoundException(attemptId);
        }
        return attempts.getFirst();
    }

    private void requireActiveToken(LockedAttempt attempt, long attemptId, String token) {
        requireToken(attempt, attemptId, token);
        requireActive(attempt, attemptId);
    }

    private void requireToken(LockedAttempt attempt, long attemptId, String token) {
        if (token == null || !MessageDigest.isEqual(
                attempt.token().getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8)
        )) {
            throw new StaleAttemptException(attemptId);
        }
    }

    private void requireActive(LockedAttempt attempt, long attemptId) {
        if (!"ACTIVE".equals(attempt.attemptStatus()) || attempt.jobState() != JobState.RUNNING) {
            throw new StaleAttemptException(attemptId);
        }
    }

    private void requireObject(JsonNode value, String name) {
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException(name + " must be a JSON object");
        }
    }

    private JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception exception) {
            throw new IllegalStateException("Stored job snapshot is not valid JSON", exception);
        }
    }

    private String resolveArtifact(String relativePath) {
        Path root = artifactProperties.root().toAbsolutePath().normalize();
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.startsWith(root) || resolved.equals(root)) {
            throw new IllegalStateException("Stored artifact path escapes the artifact root");
        }
        return resolved.toString();
    }

    private JobLogChunkResponse mapLog(java.sql.ResultSet row) throws java.sql.SQLException {
        return new JobLogChunkResponse(
                row.getLong("id"),
                row.getLong("attempt_id"),
                row.getLong("seq_no"),
                row.getString("stream"),
                row.getTimestamp("emitted_at").toInstant(),
                row.getString("content")
        );
    }

    private void broadcastAfterCommit(long jobId, JobLogChunkResponse chunk) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    logStreamService.broadcast(jobId, chunk);
                }
            });
        } else {
            logStreamService.broadcast(jobId, chunk);
        }
    }

    private record ClaimableJob(
            JobState state,
            int maxAttempts,
            JsonNode snapshot,
            String taskType,
            String artifactPath,
            String inputSha256
    ) {
    }

    private record LockedAttempt(
            long jobId,
            String attemptStatus,
            String token,
            JobState jobState,
            Instant finishedAt
    ) {
    }
}
