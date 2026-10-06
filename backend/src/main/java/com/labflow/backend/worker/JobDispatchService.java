package com.labflow.backend.worker;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Writes dispatch signals inside the caller's job-state transaction. */
@Service
public class JobDispatchService {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public JobDispatchService(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }

    public void retry(long jobId, int failedAttempt) {
        int delay = FailurePolicy.delaySeconds(failedAttempt);
        Instant publishAt = clock.instant().plusMillis(ThreadLocalRandom.current().nextLong(1001));
        jdbc.update("UPDATE jobs SET next_attempt_at = ? WHERE id = ?",
                Timestamp.from(publishAt.plusSeconds(delay)), jobId);
        enqueue(jobId, "JOB_RETRY_" + delay + "S", publishAt);
    }

    public void dead(long jobId) { enqueue(jobId, "JOB_DEAD", clock.instant()); }

    private void enqueue(long jobId, String type, Instant availableAt) {
        Long id = jdbc.queryForObject("""
                INSERT INTO outbox_events (aggregate_id, event_type, payload, created_at, available_at)
                VALUES (?, ?, '{}'::jsonb, ?, ?) RETURNING id
                """, Long.class, jobId, type, Timestamp.from(clock.instant()), Timestamp.from(availableAt));
        jdbc.update("""
                UPDATE outbox_events SET payload = jsonb_build_object(
                    'jobId', ?::bigint, 'eventId', ?::bigint, 'schemaVersion', 1) WHERE id = ?
                """, jobId, id, id);
    }
}
