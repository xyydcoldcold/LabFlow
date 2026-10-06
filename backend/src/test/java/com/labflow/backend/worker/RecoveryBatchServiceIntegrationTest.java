package com.labflow.backend.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.labflow.backend.BackendApplication;
import com.labflow.backend.worker.WorkerTestFixtures.Attempt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest(classes = BackendApplication.class, properties = {
        "labflow.outbox.publisher.enabled=false",
        "labflow.recovery.reaper.enabled=false",
        "labflow.recovery.batch-size=2"
})
class RecoveryBatchServiceIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");
    private static final String INPUT_HASH = "a".repeat(64);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired private JdbcTemplate jdbc;
    @Autowired private RecoveryBatchService recovery;
    @Autowired private WorkerExecutionService execution;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ObjectMapper objectMapper;
    @MockitoBean private Clock clock;

    @BeforeEach
    void resetDatabaseAndClock() {
        when(clock.instant()).thenReturn(NOW);
        jdbc.execute("TRUNCATE app_users, workers, outbox_events RESTART IDENTITY CASCADE");
    }

    @Test
    void expiredAttemptIsLostAndItsJobCanCompleteOnANewAttempt() {
        Attempt fixture = createAttempt(1, 3, NOW);
        assertThat(recovery.recoverBatch()).isEqualTo(new RecoveryBatchResult(1, 1, 0, 0, 0));
        assertThat(jdbc.queryForMap("SELECT status, finished_at, failure_json ->> 'code' AS code "
                + "FROM job_attempts WHERE id = ?", fixture.attemptId()))
                .containsEntry("status", "LOST")
                .containsEntry("finished_at", Timestamp.from(NOW))
                .containsEntry("code", "LEASE_EXPIRED");
        assertThat(jdbc.queryForMap("SELECT status, version, updated_at FROM jobs WHERE id = ?",
                fixture.jobId()))
                .containsEntry("status", "QUEUED")
                .containsEntry("version", 1L)
                .containsEntry("updated_at", Timestamp.from(NOW));
        assertThat(jdbc.queryForMap("""
                SELECT aggregate_id, event_type, payload ->> 'jobId' AS job_id,
                       (payload ->> 'eventId')::bigint = id AS matches_id,
                       (payload ->> 'schemaVersion')::integer AS schema_version,
                       created_at, available_at, published_at
                FROM outbox_events
                """))
                .containsEntry("aggregate_id", fixture.jobId())
                .containsEntry("event_type", "JOB_RETRY_15S")
                .containsEntry("job_id", Long.toString(fixture.jobId()))
                .containsEntry("matches_id", true)
                .containsEntry("schema_version", 1)
                .containsEntry("created_at", Timestamp.from(NOW))
                .containsEntry("published_at", null);
        assertThat(recovery.recoverBatch()).isEqualTo(new RecoveryBatchResult(0, 0, 0, 0, 0));
        assertThat(count("outbox_events")).isOne();
        assertThat(count("job_events")).isOne();

        assertThatThrownBy(() -> execution.claim(fixture.jobId(), fixture.workerId())).isInstanceOf(JobNotClaimableException.class);
        when(clock.instant()).thenReturn(NOW.plusSeconds(17));
        ClaimJobResponse takeover = execution.claim(fixture.jobId(), fixture.workerId());
        assertThat(takeover.attemptNo()).isEqualTo(2);
        assertThat(takeover.attemptToken()).isNotEqualTo(fixture.token());
        assertThatThrownBy(() -> execution.heartbeatAttempt(fixture.attemptId(), fixture.token()))
                .isInstanceOf(StaleAttemptException.class);
        assertThatThrownBy(() -> execution.appendLog(fixture.attemptId(), fixture.token(),
                new AppendLogRequest(0, "STDOUT", "late log", NOW)))
                .isInstanceOf(StaleAttemptException.class);
        CompleteAttemptRequest result = new CompleteAttemptRequest(
                objectMapper.createObjectNode().put("sha256", INPUT_HASH),
                objectMapper.createObjectNode());
        assertThatThrownBy(() -> execution.succeed(fixture.attemptId(), fixture.token(), result))
                .isInstanceOf(StaleAttemptException.class);
        assertThatThrownBy(() -> execution.fail(fixture.attemptId(), fixture.token(),
                new FailAttemptRequest(objectMapper.createObjectNode().put("code", "LATE_FAILURE"))))
                .isInstanceOf(StaleAttemptException.class);
        execution.succeed(takeover.attemptId(), takeover.attemptToken(), result);
        execution.succeed(takeover.attemptId(), takeover.attemptToken(), result);
        assertThat(count("job_results")).isOne();
        assertThat(jdbc.queryForList("SELECT status FROM job_attempts ORDER BY attempt_no", String.class))
                .containsExactly("LOST", "SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT status FROM jobs WHERE id = ?", String.class, fixture.jobId()))
                .isEqualTo("SUCCEEDED");
    }

    @Test
    void exhaustedAttemptBecomesLostAndJobFailsWithADeadLetterSignal() {
        Attempt fixture = createAttempt(3, 3, NOW.minusSeconds(1));
        assertThat(recovery.recoverBatch()).isEqualTo(new RecoveryBatchResult(1, 0, 1, 0, 0));
        assertThat(jdbc.queryForObject("SELECT status FROM jobs WHERE id = ?", String.class, fixture.jobId()))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT status FROM job_attempts WHERE id = ?", String.class,
                fixture.attemptId())).isEqualTo("LOST");
        assertThat(jdbc.queryForObject("SELECT event_type FROM job_events", String.class))
                .isEqualTo("JOB_ATTEMPTS_EXHAUSTED");
        assertThat(count("outbox_events")).isOne();
        assertThat(jdbc.queryForObject("SELECT event_type FROM outbox_events", String.class)).isEqualTo("JOB_DEAD");
        assertThatThrownBy(() -> execution.claim(fixture.jobId(), fixture.workerId()))
                .isInstanceOf(JobNotClaimableException.class);
    }

    @Test
    void scanLeavesLiveInactiveAndTerminalJobsAlone() {
        createAttempt(1, 3, NOW.plusSeconds(1));
        Attempt inactive = createAttempt(1, 3, NOW);
        jdbc.update("UPDATE job_attempts SET status = 'LOST' WHERE id = ?", inactive.attemptId());
        Attempt terminal = createAttempt(1, 3, NOW);
        jdbc.update("UPDATE jobs SET status = 'SUCCEEDED' WHERE id = ?", terminal.jobId());
        assertThat(recovery.recoverBatch()).isEqualTo(new RecoveryBatchResult(0, 0, 0, 0, 0));
        assertThat(count("outbox_events")).isZero();
        assertThat(count("job_events")).isZero();
        assertThat(jdbc.queryForList("SELECT version FROM jobs", Long.class)).containsOnly(0L);
    }

    @Test
    void scanIsBoundedAndProcessesOldestLeasesFirst() {
        Attempt newest = createAttempt(1, 3, NOW);
        Attempt oldest = createAttempt(1, 3, NOW.minusSeconds(20));
        Attempt middle = createAttempt(1, 3, NOW.minusSeconds(10));
        assertThat(recovery.recoverBatch()).isEqualTo(new RecoveryBatchResult(2, 2, 0, 0, 0));
        assertThat(jdbc.queryForList("SELECT aggregate_id FROM outbox_events ORDER BY id", Long.class))
                .containsExactly(oldest.jobId(), middle.jobId());
        assertThat(jdbc.queryForObject("SELECT status FROM jobs WHERE id = ?", String.class, newest.jobId()))
                .isEqualTo("RUNNING");
        assertThat(recovery.recoverBatch()).isEqualTo(new RecoveryBatchResult(1, 1, 0, 0, 0));
    }

    @Test
    void twoReapersDoNotRecoverTheSameAttemptWhileTheFirstTransactionIsOpen() throws Exception {
        createAttempt(1, 3, NOW);
        CountDownLatch recovered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                RecoveryBatchResult result = recovery.recoverBatch();
                recovered.countDown();
                await(release);
                return result;
            }));
            try {
                assertThat(recovered.await(10, TimeUnit.SECONDS)).isTrue();
                var second = executor.submit(recovery::recoverBatch);
                assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(new RecoveryBatchResult(0, 0, 0, 0, 0));
            } finally {
                release.countDown();
            }
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(new RecoveryBatchResult(1, 1, 0, 0, 0));
        }
        assertThat(count("outbox_events")).isOne();
        assertThat(count("job_events")).isOne();
        assertThat(jdbc.queryForObject("SELECT version FROM jobs", Long.class)).isEqualTo(1L);
    }

    @Test
    void anInFlightRenewalWinsOverRecoveryAndItsNewLeaseRemainsLive() throws Exception {
        Attempt fixture = createAttempt(1, 3, NOW.plusSeconds(1));
        CountDownLatch renewed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var heartbeat = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                AttemptHeartbeatResponse result = execution.heartbeatAttempt(fixture.attemptId(), fixture.token());
                renewed.countDown();
                await(release);
                return result;
            }));
            try {
                assertThat(renewed.await(10, TimeUnit.SECONDS)).isTrue();
                when(clock.instant()).thenReturn(NOW.plusSeconds(2));
                var scan = executor.submit(recovery::recoverBatch);
                assertThat(scan.get(10, TimeUnit.SECONDS)).isEqualTo(new RecoveryBatchResult(0, 0, 0, 0, 0));
            } finally {
                release.countDown();
            }
            assertThat(heartbeat.get(10, TimeUnit.SECONDS).leaseExpiresAt()).isEqualTo(NOW.plusSeconds(30));
        }
        assertThat(recovery.recoverBatch()).isEqualTo(new RecoveryBatchResult(0, 0, 0, 0, 0));
        assertThat(count("outbox_events")).isZero();
    }

    @Test
    void outboxFailureRollsBackAttemptJobAndHistory() {
        Attempt fixture = createAttempt(1, 3, NOW);
        jdbc.execute("ALTER TABLE outbox_events ADD CONSTRAINT recovery_test_failure "
                + "CHECK (aggregate_id <> " + fixture.jobId() + ") NOT VALID");
        try {
            assertThatThrownBy(recovery::recoverBatch).isInstanceOf(DataIntegrityViolationException.class);
            assertThat(jdbc.queryForObject("SELECT status FROM job_attempts WHERE id = ?", String.class,
                    fixture.attemptId())).isEqualTo("ACTIVE");
            assertThat(jdbc.queryForMap("SELECT status, version FROM jobs WHERE id = ?", fixture.jobId()))
                    .containsEntry("status", "RUNNING").containsEntry("version", 0L);
            assertThat(count("job_events")).isZero();
            assertThat(count("outbox_events")).isZero();
        } finally {
            jdbc.execute("ALTER TABLE outbox_events DROP CONSTRAINT recovery_test_failure");
        }
        assertThat(recovery.recoverBatch()).isEqualTo(new RecoveryBatchResult(1, 1, 0, 0, 0));
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private WorkerTestFixtures.Attempt createAttempt(int attemptNo, int maxAttempts, Instant leaseExpiresAt) {
        return WorkerTestFixtures.createAttempt(jdbc, NOW, attemptNo, maxAttempts, leaseExpiresAt);
    }
}
