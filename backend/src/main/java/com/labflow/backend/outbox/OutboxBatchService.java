package com.labflow.backend.outbox;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.labflow.backend.messaging.JobEventPublisher;
import com.labflow.backend.messaging.JobQueuedMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxBatchService {

    private static final Logger logger = LoggerFactory.getLogger(OutboxBatchService.class);

    private final JdbcTemplate jdbcTemplate;
    private final JobEventPublisher eventPublisher;
    private final OutboxProperties properties;
    private final Clock clock;

    public OutboxBatchService(
            JdbcTemplate jdbcTemplate,
            JobEventPublisher eventPublisher,
            OutboxProperties properties,
            Clock clock
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public OutboxBatchResult publishBatch() {
        Instant startedAt = clock.instant();
        List<PendingEvent> events = jdbcTemplate.query("""
                SELECT id, event_type,
                       payload ->> 'jobId' AS job_id,
                       payload ->> 'eventId' AS event_id,
                       payload ->> 'schemaVersion' AS schema_version,
                       publish_attempts
                FROM outbox_events
                WHERE published_at IS NULL AND available_at <= ?
                ORDER BY available_at, id
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """, (row, rowNumber) -> new PendingEvent(
                row.getLong("id"),
                row.getString("event_type"),
                row.getString("job_id"),
                row.getString("event_id"),
                row.getString("schema_version"),
                row.getInt("publish_attempts")
        ), Timestamp.from(startedAt), properties.batchSize());

        int published = 0;
        int failed = 0;
        for (PendingEvent event : events) {
            try {
                if (!"JOB_QUEUED".equals(event.eventType())) {
                    throw new IllegalArgumentException("Unsupported outbox event type: " + event.eventType());
                }
                eventPublisher.publish(event.toMessage());
                jdbcTemplate.update("""
                        UPDATE outbox_events
                        SET published_at = ?, last_error = NULL
                        WHERE id = ? AND published_at IS NULL
                        """, Timestamp.from(clock.instant()), event.id());
                published++;
            } catch (RuntimeException exception) {
                int nextAttempt = Math.addExact(event.publishAttempts(), 1);
                Instant retryAt = clock.instant().plus(backoffFor(nextAttempt));
                jdbcTemplate.update("""
                        UPDATE outbox_events
                        SET publish_attempts = ?, available_at = ?, last_error = ?
                        WHERE id = ? AND published_at IS NULL
                        """, nextAttempt, Timestamp.from(retryAt), summarize(exception), event.id());
                failed++;
                logger.warn("Outbox event {} publish attempt {} failed; retry scheduled for {}",
                        event.id(), nextAttempt, retryAt, exception);
            }
        }
        return new OutboxBatchResult(events.size(), published, failed);
    }

    private Duration backoffFor(int attempt) {
        int exponent = Math.min(Math.max(attempt - 1, 0), 30);
        Duration candidate;
        try {
            candidate = properties.initialBackoff().multipliedBy(1L << exponent);
        } catch (ArithmeticException exception) {
            return properties.maxBackoff();
        }
        return candidate.compareTo(properties.maxBackoff()) > 0 ? properties.maxBackoff() : candidate;
    }

    private String summarize(RuntimeException exception) {
        String message = exception.getMessage();
        String summary = exception.getClass().getSimpleName() + (message == null ? "" : ": " + message);
        return summary.length() <= 4_000 ? summary : summary.substring(0, 4_000);
    }

    private record PendingEvent(
            long id,
            String eventType,
            String jobId,
            String eventId,
            String schemaVersion,
            int publishAttempts
    ) {
        JobQueuedMessage toMessage() {
            return new JobQueuedMessage(
                    Long.parseLong(jobId),
                    Long.parseLong(eventId),
                    Integer.parseInt(schemaVersion)
            );
        }
    }
}
