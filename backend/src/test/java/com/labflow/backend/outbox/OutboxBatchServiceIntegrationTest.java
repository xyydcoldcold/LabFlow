package com.labflow.backend.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.labflow.backend.BackendApplication;
import com.labflow.backend.messaging.JobEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest(
        classes = BackendApplication.class,
        properties = "labflow.outbox.publisher.enabled=false"
)
class OutboxBatchServiceIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private OutboxBatchService batchService;

    @MockitoBean
    private JobEventPublisher eventPublisher;

    @BeforeEach
    void clearOutbox() {
        jdbcTemplate.update("DELETE FROM outbox_events");
        reset(eventPublisher);
    }

    @Test
    void confirmedEventsAreMarkedPublishedAndFailuresBackOff() {
        long eventId = insertEvent(501L);
        doThrow(new IllegalStateException("broker unavailable"))
                .when(eventPublisher).publish(any());

        OutboxBatchResult failed = batchService.publishBatch();

        assertThat(failed).isEqualTo(new OutboxBatchResult(1, 0, 1));
        var afterFailure = jdbcTemplate.queryForMap("""
                SELECT publish_attempts, available_at > created_at AS backed_off,
                       published_at, last_error
                FROM outbox_events WHERE id = ?
                """, eventId);
        assertThat(afterFailure)
                .containsEntry("publish_attempts", 1)
                .containsEntry("backed_off", true)
                .containsEntry("published_at", null);
        assertThat(afterFailure.get("last_error").toString()).contains("broker unavailable");

        reset(eventPublisher);
        jdbcTemplate.update("UPDATE outbox_events SET available_at = CURRENT_TIMESTAMP WHERE id = ?", eventId);
        OutboxBatchResult recovered = batchService.publishBatch();

        assertThat(recovered).isEqualTo(new OutboxBatchResult(1, 1, 0));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT published_at IS NOT NULL FROM outbox_events WHERE id = ?",
                Boolean.class,
                eventId
        )).isTrue();
    }

    @Test
    void skipLockedPreventsTwoPublishersFromClaimingTheSameEvent() throws Exception {
        insertEvent(502L);
        CountDownLatch publishing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            publishing.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(eventPublisher).publish(any());

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(batchService::publishBatch);
            assertThat(publishing.await(10, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(batchService::publishBatch);

            assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(new OutboxBatchResult(0, 0, 0));
            release.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(new OutboxBatchResult(1, 1, 0));
        }
    }

    @Test
    void malformedMessagesAreBackedOffWithoutBlockingLaterEvents() {
        long malformedId = jdbcTemplate.queryForObject("""
                INSERT INTO outbox_events (aggregate_id, event_type, payload)
                VALUES (503, 'JOB_QUEUED', '{"schemaVersion":2}'::jsonb)
                RETURNING id
                """, Long.class);
        long validId = insertEvent(504L);

        OutboxBatchResult result = batchService.publishBatch();

        assertThat(result).isEqualTo(new OutboxBatchResult(2, 1, 1));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT publish_attempts FROM outbox_events WHERE id = ?", Integer.class, malformedId
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT published_at IS NOT NULL FROM outbox_events WHERE id = ?", Boolean.class, validId
        )).isTrue();
    }

    private long insertEvent(long jobId) {
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO outbox_events (
                    aggregate_id, event_type, payload, created_at, available_at
                ) VALUES (?, 'JOB_QUEUED', '{}'::jsonb, ?, ?)
                RETURNING id
                """, Long.class, jobId, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
        jdbcTemplate.update("""
                UPDATE outbox_events
                SET payload = jsonb_build_object(
                    'jobId', ?::bigint, 'eventId', ?::bigint, 'schemaVersion', 1
                ) WHERE id = ?
                """, jobId, id, id);
        return id;
    }
}
