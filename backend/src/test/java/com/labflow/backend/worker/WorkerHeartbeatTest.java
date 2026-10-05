package com.labflow.backend.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import com.labflow.backend.artifact.ArtifactStorageProperties;
import com.labflow.backend.job.JobLogStreamService;
import com.labflow.backend.job.JobStateMachine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.ObjectMapper;

@SuppressWarnings("unchecked")
class WorkerHeartbeatTest {
    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ResultSet row = mock(ResultSet.class);
    private final WorkerExecutionService service = new WorkerExecutionService(
            jdbc, new ObjectMapper(),
            new WorkerProperties("test-service-token-at-least-32-bytes", Duration.ofSeconds(30)),
            mock(ArtifactStorageProperties.class), mock(JobStateMachine.class),
            mock(JobLogStreamService.class), Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void activeAttempt() throws Exception {
        when(row.getLong("job_id")).thenReturn(42L);
        when(row.getLong("worker_id")).thenReturn(7L);
        when(row.getString("attempt_status")).thenReturn("ACTIVE");
        when(row.getString("attempt_token")).thenReturn("token");
        when(row.getString("job_status")).thenReturn("RUNNING");
        when(row.getTimestamp("lease_expires_at")).thenReturn(Timestamp.from(NOW.plusSeconds(10)));
        doAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(row, 0));
        }).when(jdbc).query(anyString(), any(RowMapper.class), eq(9L));
    }

    @Test
    void renewalUsesBackendClockAndRefreshesWorkerLiveness() {
        AttemptHeartbeatResponse response = service.heartbeatAttempt(9, "token");
        assertThat(response.leaseExpiresAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(response.cancelRequested()).isFalse();
        verify(jdbc).update("UPDATE job_attempts SET lease_expires_at = ? WHERE id = ?",
                Timestamp.from(NOW.plusSeconds(30)), 9L);
        verify(jdbc).update("UPDATE workers SET last_heartbeat_at = ? WHERE id = ?",
                Timestamp.from(NOW), 7L);
    }

    @Test
    void cancellationFlagIsReturnedToWorker() throws Exception {
        when(row.getTimestamp("cancel_requested_at")).thenReturn(Timestamp.from(NOW));
        assertThat(service.heartbeatAttempt(9, "token").cancelRequested()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong", ""})
    void wrongTokenCannotRenew(String token) {
        assertStale(token);
    }

    @ParameterizedTest
    @ValueSource(strings = {"LOST", "SUCCEEDED", "FAILED", "CANCELLED"})
    void inactiveAttemptCannotRenew(String status) throws Exception {
        when(row.getString("attempt_status")).thenReturn(status);
        assertStale("token");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void expiredLeaseCannotBeRevivedAtItsBoundary(int offset) throws Exception {
        when(row.getTimestamp("lease_expires_at")).thenReturn(Timestamp.from(NOW.plusSeconds(offset)));
        assertStale("token");
    }

    @Test
    void nonRunningJobCannotRenew() throws Exception {
        when(row.getString("job_status")).thenReturn("CANCELLED");
        assertStale("token");
    }

    @Test
    void idleWorkerHeartbeatUsesBackendClock() {
        when(jdbc.update(anyString(), any(Timestamp.class), eq(7L))).thenReturn(1);
        assertThat(service.heartbeatWorker(7).lastHeartbeatAt()).isEqualTo(NOW);
        verify(jdbc).update("UPDATE workers SET last_heartbeat_at = ? WHERE id = ?",
                Timestamp.from(NOW), 7L);
    }

    @Test
    void unknownWorkerIsRejected() {
        assertThatThrownBy(() -> service.heartbeatWorker(404)).isInstanceOf(WorkerNotFoundException.class);
    }

    private void assertStale(String token) {
        assertThatThrownBy(() -> service.heartbeatAttempt(9, token)).isInstanceOf(StaleAttemptException.class);
        verify(jdbc).query(anyString(), any(RowMapper.class), eq(9L));
        verifyNoMoreInteractions(jdbc);
    }
}
