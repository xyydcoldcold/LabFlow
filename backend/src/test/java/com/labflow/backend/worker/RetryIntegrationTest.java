package com.labflow.backend.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;

import com.labflow.backend.BackendApplication;
import com.labflow.backend.job.JobReplayService;
import com.labflow.backend.messaging.RabbitTopology;
import com.labflow.backend.outbox.OutboxBatchService;
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
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest(classes = BackendApplication.class, properties = {
    "labflow.outbox.publisher.enabled=false", "labflow.recovery.reaper.enabled=false"
})
class RetryIntegrationTest {
    static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");
    @Container @ServiceConnection static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");
    @Container @ServiceConnection static final RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:4-management-alpine");
    @Autowired JdbcTemplate jdbc;
    @Autowired WorkerExecutionService execution;
    @Autowired OutboxBatchService outbox;
    @Autowired JobReplayService replays;
    @Autowired ObjectMapper mapper;
    @Autowired RabbitAdmin admin;
    @Autowired RabbitTemplate template;
    @MockitoBean Clock clock;

    @BeforeEach void reset() {
        when(clock.instant()).thenReturn(NOW);
        jdbc.execute("TRUNCATE app_users, workers, outbox_events RESTART IDENTITY CASCADE");
        for (String queue : new String[]{RabbitTopology.JOBS_QUEUE, RabbitTopology.RETRY_15S_QUEUE,
                RabbitTopology.RETRY_60S_QUEUE, RabbitTopology.RETRY_300S_QUEUE, RabbitTopology.JOBS_DLQ})
            admin.purgeQueue(queue);
    }

    @Test void transientFailureCommitsOneDelayedSignalAndReturnsThroughRealTtlQueue() {
        var attempt = WorkerTestFixtures.createAttempt(jdbc, NOW, 1, 4, NOW.plusSeconds(30));
        var failure = new FailAttemptRequest(mapper.createObjectNode().put("code", "TRANSIENT_TASK_ERROR"));
        execution.fail(attempt.attemptId(), attempt.token(), failure);
        execution.fail(attempt.attemptId(), attempt.token(), failure);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT status FROM job_attempts", String.class)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT status FROM jobs", String.class)).isEqualTo("QUEUED");
        assertThatThrownBy(() -> execution.claim(attempt.jobId(), attempt.workerId())).isInstanceOf(JobNotClaimableException.class);
        Instant due = jdbc.queryForObject("SELECT next_attempt_at FROM jobs", Timestamp.class).toInstant();
        assertThat(due).isBetween(NOW.plusSeconds(15), NOW.plusSeconds(16));
        when(clock.instant()).thenReturn(NOW.plusSeconds(2));
        assertThat(outbox.publishBatch().published()).isOne();
        assertThat(admin.getQueueInfo(RabbitTopology.RETRY_15S_QUEUE).getMessageCount()).isEqualTo(1);
        assertThat(template.receive(RabbitTopology.JOBS_QUEUE)).isNull();
        var delivered = template.receive(RabbitTopology.JOBS_QUEUE, 18_000);
        assertThat(delivered).isNotNull();
        when(clock.instant()).thenReturn(due);
        assertThat(execution.claim(attempt.jobId(), attempt.workerId()).attemptNo()).isEqualTo(2);
    }

    @Test void secondAndThirdRetryLevelsAreRoutedToTheirDeclaredQueues() {
        int[] attempts = {2, 3};
        String[] queues = {RabbitTopology.RETRY_60S_QUEUE, RabbitTopology.RETRY_300S_QUEUE};
        for (int i = 0; i < attempts.length; i++) {
            when(clock.instant()).thenReturn(NOW);
            var attempt = WorkerTestFixtures.createAttempt(jdbc, NOW, attempts[i], 4, NOW.plusSeconds(30));
            execution.fail(attempt.attemptId(), attempt.token(), new FailAttemptRequest(mapper.createObjectNode().put("code", "NETWORK_ERROR")));
            when(clock.instant()).thenReturn(NOW.plusSeconds(2));
            assertThat(outbox.publishBatch().published()).isOne();
            assertThat(template.receive(queues[i], 3000)).isNotNull();
        }
    }

    @Test void exhaustedTransientFailureIsDeadLetteredOnceAndReplayPreservesTheOriginal() {
        var attempt = WorkerTestFixtures.createAttempt(jdbc, NOW, 4, 4, NOW.plusSeconds(30));
        var failure = new FailAttemptRequest(mapper.createObjectNode().put("code", "NETWORK_ERROR"));
        execution.fail(attempt.attemptId(), attempt.token(), failure);
        execution.fail(attempt.attemptId(), attempt.token(), failure);
        assertThat(outbox.publishBatch().published()).isOne();
        assertThat(template.receive(RabbitTopology.JOBS_DLQ, 3000)).isNotNull();
        assertThat(template.receive(RabbitTopology.JOBS_DLQ)).isNull();
        var replay = replays.replay(attempt.jobId(), attempt.userId(), "replay-key");
        assertThat(replays.replay(attempt.jobId(), attempt.userId(), "replay-key").id()).isEqualTo(replay.id());
        assertThat(replay.id()).isNotEqualTo(attempt.jobId());
        assertThat(jdbc.queryForObject("SELECT status FROM jobs WHERE id = ?", String.class, attempt.jobId())).isEqualTo("FAILED");
        assertThat(jdbc.queryForMap("SELECT status, max_attempts, replayed_from_job_id FROM jobs WHERE id = ?", replay.id()))
            .containsEntry("status", "QUEUED").containsEntry("max_attempts", 4).containsEntry("replayed_from_job_id", attempt.jobId());
    }

    @Test void permanentAndUnknownFailuresDoNotRetryOrProduceDeadLetters() {
        for (String code : new String[]{"SCF_NOT_CONVERGED", "INVALID_INPUT", "UNKNOWN_NEW_ERROR"}) {
            var attempt = WorkerTestFixtures.createAttempt(jdbc, NOW, 1, 4, NOW.plusSeconds(30));
            execution.fail(attempt.attemptId(), attempt.token(), new FailAttemptRequest(mapper.createObjectNode().put("code", code)));
            assertThat(jdbc.queryForObject("SELECT status FROM jobs WHERE id = ?", String.class, attempt.jobId())).isEqualTo("FAILED");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isZero();
    }
}
