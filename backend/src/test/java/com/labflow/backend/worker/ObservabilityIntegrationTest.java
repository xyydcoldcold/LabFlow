package com.labflow.backend.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import com.labflow.backend.BackendApplication;
import com.labflow.backend.auth.AppUserRepository;
import com.labflow.backend.auth.JwtTokenService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.node.JsonNodeFactory;

@Testcontainers
@SpringBootTest(classes = BackendApplication.class, properties = {
        "labflow.outbox.publisher.enabled=false", "labflow.recovery.reaper.enabled=false",
        "labflow.metrics.scrape-token=isolated-test-metrics-token"})
@AutoConfigureMockMvc
@AutoConfigureMetrics
class ObservabilityIntegrationTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MeterRegistry registry;
    @Autowired private WorkerExecutionService execution;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private MockMvc mvc;
    @Autowired private AppUserRepository users;
    @Autowired private JwtTokenService tokens;

    @Test
    void lifecycleCountersObserveCommitNotRollbackAndDoNotCountDuplicateCompletion() {
        Instant now = Instant.now();
        var attempt = WorkerTestFixtures.createAttempt(jdbc, now, 1, 4, now.plusSeconds(60));
        var counter = registry.counter("labflow.job.events", "event", "SUCCEEDED");
        double before = counter.count();
        var result = new CompleteAttemptRequest(JsonNodeFactory.instance.objectNode().put("sha256", "test"),
                JsonNodeFactory.instance.objectNode());
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            execution.succeed(attempt.attemptId(), attempt.token(), result);
            assertThat(counter.count()).isEqualTo(before);
            status.setRollbackOnly();
        });
        assertThat(counter.count()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM job_results WHERE job_id = ?", Long.class, attempt.jobId())).isZero();
        execution.succeed(attempt.attemptId(), attempt.token(), result);
        execution.succeed(attempt.attemptId(), attempt.token(), result);
        assertThat(counter.count()).isEqualTo(before + 1);
    }

    @Test
    void gaugesMatchDatabaseStateAndMetricLabelsDoNotContainResourceIds() {
        Instant now = Instant.now();
        WorkerTestFixtures.createAttempt(jdbc, now, 1, 4, now.plusSeconds(60));
        assertThat(registry.get("labflow.jobs").tag("status", "RUNNING").gauge().value())
                .isEqualTo(jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE status = 'RUNNING'", Long.class).doubleValue());
        assertThat(registry.get("labflow.attempts").tag("status", "ACTIVE").gauge().value())
                .isEqualTo(jdbc.queryForObject("SELECT COUNT(*) FROM job_attempts WHERE status = 'ACTIVE'", Long.class).doubleValue());
        registry.getMeters().stream().filter(meter -> meter.getId().getName().startsWith("labflow."))
                .forEach(meter -> assertThat(meter.getId().getTags()).allMatch(tag ->
                        tag.getKey().equals("status") || tag.getKey().equals("event")));
    }

    @Test
    void onlyDedicatedScrapeCredentialCanReadMetricsAndErrorsCarryGeneratedRequestIds() throws Exception {
        Instant now = Instant.now();
        var attempt = WorkerTestFixtures.createAttempt(jdbc, now, 1, 4, now.plusSeconds(60));
        String jwt = tokens.issueToken(users.findById(attempt.userId()).orElseThrow());
        for (String token : new String[] {"wrong", jwt, "labflow-local-worker-service-token-change-me"}) {
            var denied = mvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized()).andReturn().getResponse();
            assertThat(denied.getHeader("X-Request-ID")).isNotBlank();
            assertThat(denied.getContentAsString()).contains(denied.getHeader("X-Request-ID"));
        }
        var allowed = mvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer isolated-test-metrics-token"))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(allowed.getContentAsString()).contains("labflow_job_events_total", "labflow_jobs");
    }
}
