package com.labflow.backend.worker;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import com.labflow.backend.job.JobState;
import com.labflow.backend.job.JobStateMachine;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecoveryBatchService {

    private final JdbcTemplate jdbcTemplate;
    private final RecoveryProperties properties;
    private final JobStateMachine stateMachine;
    private final Clock clock;
    private final JobDispatchService dispatch;

    public RecoveryBatchService(
            JdbcTemplate jdbcTemplate,
            RecoveryProperties properties,
            JobStateMachine stateMachine,
            Clock clock,
            JobDispatchService dispatch
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.stateMachine = stateMachine;
        this.clock = clock;
        this.dispatch = dispatch;
    }

    @Transactional
    public RecoveryBatchResult recoverBatch() {
        Instant now = clock.instant();
        List<ExpiredAttempt> attempts = jdbcTemplate.query("""
                SELECT a.id AS attempt_id, a.job_id, a.attempt_no, j.max_attempts, j.version,
                       j.cancel_requested_at IS NOT NULL AS cancel_requested
                FROM job_attempts a
                JOIN jobs j ON j.id = a.job_id
                WHERE a.status = 'ACTIVE' AND a.lease_expires_at <= ?
                  AND j.status = 'RUNNING'
                ORDER BY a.lease_expires_at, a.id
                LIMIT ?
                FOR UPDATE OF a, j SKIP LOCKED
                """, (row, rowNumber) -> new ExpiredAttempt(
                row.getLong("attempt_id"),
                row.getLong("job_id"),
                row.getInt("attempt_no"),
                row.getInt("max_attempts"),
                row.getLong("version"),
                row.getBoolean("cancel_requested")
        ), Timestamp.from(now), properties.batchSize());

        int requeued = 0;
        int exhausted = 0;
        int cancelled = 0;
        int skipped = 0;
        for (ExpiredAttempt attempt : attempts) {
            boolean canRetry = !attempt.cancelRequested() && attempt.attemptNo() < attempt.maxAttempts();
            JobState nextState = attempt.cancelRequested() ? JobState.CANCELLED
                    : canRetry ? JobState.QUEUED : JobState.FAILED;
            stateMachine.requireTransition(JobState.RUNNING, nextState);
            int updated = jdbcTemplate.update("""
                    UPDATE jobs
                    SET status = ?, version = version + 1, updated_at = ?
                    WHERE id = ? AND status = 'RUNNING' AND version = ?
                      AND (cancel_requested_at IS NOT NULL) = ?
                    """, nextState.name(), Timestamp.from(now), attempt.jobId(), attempt.jobVersion(), attempt.cancelRequested());
            if (updated == 0) {
                skipped++;
                continue;
            }
            requireOneRow(updated, "job");
            requireOneRow(jdbcTemplate.update("""
                    UPDATE job_attempts
                    SET status = 'LOST', finished_at = ?, failure_json = jsonb_build_object(
                        'code', 'LEASE_EXPIRED', 'message', 'Attempt lease expired before renewal'
                    )
                    WHERE id = ? AND status = 'ACTIVE' AND lease_expires_at <= ?
                    """, Timestamp.from(now), attempt.attemptId(), Timestamp.from(now)), "attempt");
            jdbcTemplate.update("""
                    INSERT INTO job_events (job_id, from_status, to_status, event_type, details, created_at)
                    VALUES (?, 'RUNNING', ?, ?, jsonb_build_object(
                        'attemptId', ?::bigint, 'attemptNo', ?::integer,
                        'reason', ?::text, 'maxAttempts', ?::integer
                    ), ?)
                    """, attempt.jobId(), nextState.name(),
                    attempt.cancelRequested() ? "JOB_CANCELLED"
                            : canRetry ? "JOB_RECOVERED" : "JOB_ATTEMPTS_EXHAUSTED",
                    attempt.attemptId(), attempt.attemptNo(),
                    attempt.cancelRequested() ? "CANCEL_REQUESTED" : "LEASE_EXPIRED",
                    attempt.maxAttempts(), Timestamp.from(now));
            if (canRetry) {
                dispatch.retry(attempt.jobId(), attempt.attemptNo());
                requeued++;
            } else if (attempt.cancelRequested()) {
                cancelled++;
            } else {
                dispatch.dead(attempt.jobId());
                exhausted++;
            }
        }
        return new RecoveryBatchResult(attempts.size(), requeued, exhausted, cancelled, skipped);
    }

    private void requireOneRow(int updated, String resource) {
        if (updated != 1) {
            throw new IllegalStateException("Recovery could not update exactly one " + resource);
        }
    }

    private record ExpiredAttempt(
            long attemptId,
            long jobId,
            int attemptNo,
            int maxAttempts,
            long jobVersion,
            boolean cancelRequested
    ) {
    }
}
