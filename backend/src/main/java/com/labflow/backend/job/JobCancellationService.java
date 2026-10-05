package com.labflow.backend.job;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import com.labflow.backend.project.ProjectAccessDeniedException;
import com.labflow.backend.project.ProjectPermissionService;
import com.labflow.backend.project.ProjectRole;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JobCancellationService {
    private final JdbcTemplate jdbcTemplate;
    private final ProjectPermissionService permissionService;
    private final JobStateMachine stateMachine;
    private final Clock clock;

    public JobCancellationService(
            JdbcTemplate jdbcTemplate,
            ProjectPermissionService permissionService,
            JobStateMachine stateMachine,
            Clock clock
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.permissionService = permissionService;
        this.stateMachine = stateMachine;
        this.clock = clock;
    }

    @Transactional
    public JobCancellationResponse cancel(long jobId, long userId) {
        List<CancellableJob> jobs = jdbcTemplate.query("""
                SELECT project_id, submitted_by, status, cancel_requested_at
                FROM jobs WHERE id = ? FOR UPDATE
                """, (row, rowNumber) -> new CancellableJob(
                row.getLong("project_id"), row.getLong("submitted_by"),
                JobState.valueOf(row.getString("status")),
                row.getTimestamp("cancel_requested_at") == null ? null
                        : row.getTimestamp("cancel_requested_at").toInstant()
        ), jobId);
        if (jobs.isEmpty()) {
            throw new JobNotFoundException(jobId);
        }
        CancellableJob job = jobs.getFirst();
        ProjectRole role = permissionService.requireContribute(job.projectId(), userId);
        if (job.submittedBy() != userId && role != ProjectRole.OWNER && role != ProjectRole.MAINTAINER) {
            throw new ProjectAccessDeniedException(job.projectId());
        }
        if (job.status() == JobState.CANCELLED) {
            return new JobCancellationResponse(jobId, job.status(), job.cancelRequestedAt());
        }
        if (job.status().isTerminal()) {
            throw new JobNotCancellableException(jobId);
        }
        if (job.cancelRequestedAt() != null) {
            return new JobCancellationResponse(jobId, job.status(), job.cancelRequestedAt());
        }

        Instant now = clock.instant();
        JobState nextState = job.status() == JobState.QUEUED ? JobState.CANCELLED : JobState.RUNNING;
        if (nextState == JobState.CANCELLED) {
            stateMachine.requireTransition(job.status(), nextState);
        }
        jdbcTemplate.update("""
                UPDATE jobs SET status = ?, cancel_requested_at = ?, updated_at = ?, version = version + 1
                WHERE id = ?
                """, nextState.name(), Timestamp.from(now), Timestamp.from(now), jobId);
        if (nextState == JobState.CANCELLED) {
            jdbcTemplate.update("""
                    INSERT INTO job_events (job_id, from_status, to_status, event_type, details, created_at)
                    VALUES (?, 'QUEUED', 'CANCELLED', 'JOB_CANCELLED',
                            jsonb_build_object('requestedBy', ?::bigint, 'reason', 'USER_REQUEST'), ?)
                    """, jobId, userId, Timestamp.from(now));
        }
        return new JobCancellationResponse(jobId, nextState, now);
    }

    private record CancellableJob(long projectId, long submittedBy, JobState status, Instant cancelRequestedAt) {
    }
}
