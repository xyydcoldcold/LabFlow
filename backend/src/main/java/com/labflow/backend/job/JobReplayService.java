package com.labflow.backend.job;

import com.labflow.backend.project.ProjectPermissionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JobReplayService {
    private final JdbcTemplate jdbc;
    private final ProjectPermissionService permissions;
    private final JobSubmissionService submissions;
    public JobReplayService(JdbcTemplate jdbc, ProjectPermissionService permissions, JobSubmissionService submissions) {
        this.jdbc = jdbc; this.permissions = permissions; this.submissions = submissions;
    }

    @Transactional
    public JobResponse replay(long jobId, long userId, String key) {
        var jobs = jdbc.queryForList("SELECT project_id, molecular_input_id, experiment_config_id, status FROM jobs WHERE id = ? FOR UPDATE", jobId);
        if (jobs.isEmpty()) throw new JobNotFoundException(jobId);
        var job = jobs.getFirst();
        long projectId = ((Number) job.get("project_id")).longValue();
        permissions.requireMaintain(projectId, userId);
        if (!"FAILED".equals(job.get("status"))) throw new IllegalArgumentException("Only failed jobs can be replayed");
        if (key == null || key.isBlank() || !key.equals(key.trim()) || key.length() > 180)
            throw new IllegalArgumentException("Replay Idempotency-Key must be 1 to 180 characters without surrounding whitespace");
        JobResponse replay = submissions.submit(userId, "replay:" + jobId + ":" + key,
                new CreateJobRequest(projectId, ((Number) job.get("molecular_input_id")).longValue(),
                        ((Number) job.get("experiment_config_id")).longValue()));
        jdbc.update("UPDATE jobs SET replayed_from_job_id = ? WHERE id = ? AND replayed_from_job_id IS NULL", jobId, replay.id());
        return replay;
    }
}
