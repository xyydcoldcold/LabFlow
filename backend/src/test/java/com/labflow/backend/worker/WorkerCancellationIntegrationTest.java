package com.labflow.backend.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.labflow.backend.BackendApplication;
import com.labflow.backend.job.JobCancellationService;
import com.labflow.backend.job.JobNotCancellableException;
import com.labflow.backend.worker.WorkerTestFixtures.Attempt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
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
        "labflow.worker.attempt-lease=PT30S"
})
@AutoConfigureMockMvc
class WorkerCancellationIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");
    private static final String SERVICE_TOKEN = "labflow-local-worker-service-token-change-me";
    private static final String RESULT = "{\"summary\":{},\"manifest\":{}}";
    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;
    @Autowired private WorkerExecutionService execution;
    @Autowired private JobCancellationService cancellation;
    @Autowired private RecoveryBatchService recovery;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ObjectMapper mapper;
    @MockitoBean private Clock clock;

    @BeforeEach
    void resetDatabase() {
        when(clock.instant()).thenReturn(NOW);
        jdbc.execute("TRUNCATE app_users, workers, outbox_events RESTART IDENTITY CASCADE");
    }

    @Test
    void expiredLeaseRejectsEveryWorkerWriteBeforeTheReaperRuns() throws Exception {
        Attempt attempt = fixture(NOW);
        internal(attempt, "heartbeat", "{}", 409, "STALE_ATTEMPT");
        internal(attempt, "logs", """
                {"seqNo":0,"stream":"STDOUT","content":"late","emittedAt":"2026-10-05T12:00:00Z"}
                """, 409, "STALE_ATTEMPT");
        internal(attempt, "succeed", RESULT, 409, "STALE_ATTEMPT");
        internal(attempt, "fail", "{\"error\":{\"code\":\"LATE\"}}", 409, "STALE_ATTEMPT");
        internal(attempt, "cancelled", "{}", 409, "STALE_ATTEMPT");
        assertThat(count("job_results")).isZero();
        assertThat(count("job_log_chunks")).isZero();
        assertThat(attemptStatus(attempt)).isEqualTo("ACTIVE");
        assertThat(jobStatus(attempt)).isEqualTo("RUNNING");
    }

    @Test
    void queuedCancellationIsImmediateAndIdempotentAndPreventsClaim() throws Exception {
        Attempt attempt = fixture(NOW.plusSeconds(30));
        jdbc.update("DELETE FROM job_attempts WHERE id = ?", attempt.attemptId());
        jdbc.update("UPDATE jobs SET status = 'QUEUED' WHERE id = ?", attempt.jobId());
        requestCancel(attempt, attempt.userId(), 200, "CANCELLED");
        when(clock.instant()).thenReturn(NOW.plusSeconds(1));
        requestCancel(attempt, attempt.userId(), 200, "CANCELLED");
        assertThatThrownBy(() -> execution.claim(attempt.jobId(), attempt.workerId()))
                .isInstanceOf(JobNotClaimableException.class);
        assertThat(count("job_events")).isOne();
        assertThat(count("job_attempts")).isZero();
        assertThat(count("outbox_events")).isZero();
        assertThat(jdbc.queryForMap("SELECT version, cancel_requested_at FROM jobs"))
                .containsEntry("version", 1L).containsEntry("cancel_requested_at", Timestamp.from(NOW));
    }

    @Test
    void runningCancellationBlocksCompletionAndIsConfirmedByTheWorker() throws Exception {
        Attempt attempt = fixture(NOW.plusSeconds(30));
        requestCancel(attempt, attempt.userId(), 200, "RUNNING");
        requestCancel(attempt, attempt.userId(), 200, "RUNNING");
        mvc.perform(get("/api/jobs/{id}", attempt.jobId()).with(jwt().jwt(j -> j.subject(Long.toString(attempt.userId())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canCancel").value(true))
                .andExpect(jsonPath("$.cancelRequestedAt").value(NOW.toString()));
        mvc.perform(workerRequest(attempt, "heartbeat", "{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cancelRequested").value(true));
        internal(attempt, "succeed", RESULT, 409, "CANCEL_REQUESTED");
        internal(attempt, "fail", "{\"error\":{}}", 409, "CANCEL_REQUESTED");
        internal(attempt, "cancelled", "{}", 200, null);
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));
        internal(attempt, "cancelled", "{}", 200, null);
        requestCancel(attempt, attempt.userId(), 200, "CANCELLED");
        internal(attempt, "heartbeat", "{}", 409, "STALE_ATTEMPT");
        assertThat(jobStatus(attempt)).isEqualTo("CANCELLED");
        assertThat(attemptStatus(attempt)).isEqualTo("CANCELLED");
        assertThat(count("job_results")).isZero();
        assertThat(count("job_events")).isOne();
        assertThat(jdbc.queryForObject("SELECT version FROM jobs", Long.class)).isEqualTo(2L);
    }

    @Test
    void workerCannotConfirmCancellationWithoutAUserRequestOrValidToken() throws Exception {
        Attempt attempt = fixture(NOW.plusSeconds(30));
        internal(attempt, "cancelled", "{}", 400, null);
        requestCancel(attempt, attempt.userId(), 200, "RUNNING");
        mvc.perform(post("/internal/attempts/{id}/cancelled", attempt.attemptId())
                        .header("Authorization", "Bearer " + SERVICE_TOKEN)
                        .header("X-Attempt-Token", "wrong"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_ATTEMPT"));
        assertThat(jobStatus(attempt)).isEqualTo("RUNNING");
    }

    @Test
    void cancellationEndpointsRequireAuthentication() throws Exception {
        Attempt attempt = fixture(NOW.plusSeconds(30));
        mvc.perform(post("/api/jobs/{id}/cancel", attempt.jobId())).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/attempts/{id}/cancelled", attempt.attemptId())
                .header("X-Attempt-Token", attempt.token())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/jobs/999999/cancel").with(jwt().jwt(j -> j.subject(Long.toString(attempt.userId())))))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"VIEWER", "MEMBER", "OUTSIDER"})
    void viewersOutsidersAndOtherMembersCannotCancelSomeoneElsesJob(String role) throws Exception {
        Attempt attempt = fixture(NOW.plusSeconds(30));
        long userId = user();
        if (!role.equals("OUTSIDER")) {
            jdbc.update("INSERT INTO project_members (project_id, user_id, role) VALUES (?, ?, ?)",
                    attempt.projectId(), userId, role);
        }
        mvc.perform(get("/api/jobs/{id}", attempt.jobId()).with(jwt().jwt(j -> j.subject(Long.toString(userId)))))
                .andExpect(status().is(role.equals("OUTSIDER") ? 403 : 200));
        if (!role.equals("OUTSIDER")) {
            mvc.perform(get("/api/jobs/{id}", attempt.jobId()).with(jwt().jwt(j -> j.subject(Long.toString(userId)))))
                    .andExpect(jsonPath("$.canCancel").value(false));
        }
        requestCancel(attempt, userId, 403, null);
        assertThat(jdbc.queryForObject("SELECT cancel_requested_at IS NULL FROM jobs", Boolean.class)).isTrue();
    }

    @Test
    void contributingSubmitterAndMaintainerCanCancel() throws Exception {
        Attempt first = fixture(NOW.plusSeconds(30));
        long member = user();
        jdbc.update("INSERT INTO project_members (project_id, user_id, role) VALUES (?, ?, 'MEMBER')",
                first.projectId(), member);
        jdbc.update("UPDATE jobs SET submitted_by = ? WHERE id = ?", member, first.jobId());
        requestCancel(first, member, 200, "RUNNING");
        Attempt second = fixture(NOW.plusSeconds(30));
        long maintainer = user();
        jdbc.update("INSERT INTO project_members (project_id, user_id, role) VALUES (?, ?, 'MAINTAINER')",
                second.projectId(), maintainer);
        requestCancel(second, maintainer, 200, "RUNNING");
    }

    @Test
    void expiredCancellationIsFinalizedWithoutRequeueEvenAtTheAttemptLimit() {
        Attempt attempt = WorkerTestFixtures.createAttempt(jdbc, NOW, 3, 3, NOW);
        cancellation.cancel(attempt.jobId(), attempt.userId());
        assertThat(recovery.recoverBatch()).isEqualTo(new RecoveryBatchResult(1, 0, 0, 1, 0));
        assertThat(attemptStatus(attempt)).isEqualTo("LOST");
        assertThat(jobStatus(attempt)).isEqualTo("CANCELLED");
        assertThat(count("outbox_events")).isZero();
        assertThat(jdbc.queryForObject("SELECT event_type FROM job_events", String.class)).isEqualTo("JOB_CANCELLED");
        assertThat(recovery.recoverBatch()).isEqualTo(new RecoveryBatchResult(0, 0, 0, 0, 0));
    }

    @Test
    void committedCancellationWinsOverRacingResult() throws Exception {
        Attempt attempt = fixture(NOW.plusSeconds(30));
        race(() -> cancellation.cancel(attempt.jobId(), attempt.userId()),
                () -> assertThatThrownBy(() -> execution.succeed(attempt.attemptId(), attempt.token(), result()))
                        .isInstanceOf(AttemptCancellationRequestedException.class));
        assertThat(count("job_results")).isZero();
        execution.cancelled(attempt.attemptId(), attempt.token());
        assertThat(jobStatus(attempt)).isEqualTo("CANCELLED");
    }

    @Test
    void committedResultWinsOverRacingCancellationAndRemainsReplayableAfterExpiry() throws Exception {
        Attempt attempt = fixture(NOW.plusSeconds(30));
        race(() -> execution.succeed(attempt.attemptId(), attempt.token(), result()),
                () -> assertThatThrownBy(() -> cancellation.cancel(attempt.jobId(), attempt.userId()))
                        .isInstanceOf(JobNotCancellableException.class));
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));
        internal(attempt, "succeed", RESULT, 200, null);
        requestCancel(attempt, attempt.userId(), 409, null);
        assertThat(count("job_results")).isOne();
        assertThat(jobStatus(attempt)).isEqualTo("SUCCEEDED");
    }

    @Test
    void recoveryCanWinBeforeCancellationButItsQueuedJobThenCancels() throws Exception {
        Attempt attempt = fixture(NOW);
        race(recovery::recoverBatch, () -> cancellation.cancel(attempt.jobId(), attempt.userId()));
        assertThat(jobStatus(attempt)).isEqualTo("CANCELLED");
        assertThat(attemptStatus(attempt)).isEqualTo("LOST");
        assertThat(count("outbox_events")).isOne();
        assertThatThrownBy(() -> execution.claim(attempt.jobId(), attempt.workerId()))
                .isInstanceOf(JobNotClaimableException.class);
    }

    @Test
    void queuedCancellationWinsOverAConcurrentWorkerClaim() throws Exception {
        Attempt attempt = queuedFixture();
        race(() -> cancellation.cancel(attempt.jobId(), attempt.userId()),
                () -> assertThatThrownBy(() -> execution.claim(attempt.jobId(), attempt.workerId()))
                        .isInstanceOf(JobNotClaimableException.class));
        assertThat(count("job_attempts")).isZero();
        assertThat(jobStatus(attempt)).isEqualTo("CANCELLED");
    }

    @Test
    void aWorkerClaimThatWinsFirstReceivesThePendingCancellation() throws Exception {
        Attempt attempt = queuedFixture();
        var claimed = new java.util.concurrent.atomic.AtomicReference<ClaimJobResponse>();
        race(() -> claimed.set(execution.claim(attempt.jobId(), attempt.workerId())),
                () -> cancellation.cancel(attempt.jobId(), attempt.userId()));
        ClaimJobResponse claim = claimed.get();
        assertThat(execution.heartbeatAttempt(claim.attemptId(), claim.attemptToken()).cancelRequested()).isTrue();
        execution.cancelled(claim.attemptId(), claim.attemptToken());
        assertThat(jobStatus(attempt)).isEqualTo("CANCELLED");
        assertThat(count("job_results")).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM job_attempts", String.class)).isEqualTo("CANCELLED");
    }

    private Attempt queuedFixture() {
        Attempt attempt = fixture(NOW.plusSeconds(30));
        jdbc.update("DELETE FROM job_attempts WHERE id = ?", attempt.attemptId());
        jdbc.update("UPDATE jobs SET status = 'QUEUED' WHERE id = ?", attempt.jobId());
        return attempt;
    }

    private void race(Runnable first, Runnable second) throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var winner = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                first.run();
                held.countDown();
                try {
                    assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            try {
                assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
                var loser = executor.submit(second);
                assertThatThrownBy(() -> loser.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown();
                winner.get(10, TimeUnit.SECONDS);
                loser.get(10, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
        }
    }

    private Attempt fixture(Instant lease) {
        return WorkerTestFixtures.createAttempt(jdbc, NOW, 1, 3, lease);
    }

    private CompleteAttemptRequest result() {
        return new CompleteAttemptRequest(mapper.createObjectNode(), mapper.createObjectNode());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder workerRequest(
            Attempt attempt, String action, String body
    ) {
        return post("/internal/attempts/{id}/" + action, attempt.attemptId())
                .header("Authorization", "Bearer " + SERVICE_TOKEN)
                .header("X-Attempt-Token", attempt.token()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private void internal(Attempt attempt, String action, String body, int expectedStatus, String code) throws Exception {
        var response = mvc.perform(workerRequest(attempt, action, body)).andExpect(status().is(expectedStatus));
        if (code != null) response.andExpect(jsonPath("$.code").value(code));
    }

    private void requestCancel(Attempt attempt, long userId, int expectedStatus, String state) throws Exception {
        var response = mvc.perform(post("/api/jobs/{id}/cancel", attempt.jobId())
                .with(jwt().jwt(j -> j.subject(Long.toString(userId))))).andExpect(status().is(expectedStatus));
        if (state != null) response.andExpect(jsonPath("$.status").value(state));
    }

    private long user() {
        return jdbc.queryForObject("INSERT INTO app_users (email, password_hash, display_name) "
                + "VALUES (?, 'test', 'Test') RETURNING id", Long.class, UUID.randomUUID() + "@example.com");
    }

    private String jobStatus(Attempt attempt) {
        return jdbc.queryForObject("SELECT status FROM jobs WHERE id = ?", String.class, attempt.jobId());
    }

    private String attemptStatus(Attempt attempt) {
        return jdbc.queryForObject("SELECT status FROM job_attempts WHERE id = ?", String.class, attempt.attemptId());
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
