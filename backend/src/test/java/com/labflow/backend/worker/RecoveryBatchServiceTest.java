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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import com.labflow.backend.job.JobStateMachine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@SuppressWarnings("unchecked")
class RecoveryBatchServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final RecoveryBatchService service = new RecoveryBatchService(
            jdbc, new RecoveryProperties(50), new JobStateMachine(), Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void candidate() throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getLong("attempt_id")).thenReturn(9L);
        when(row.getLong("job_id")).thenReturn(42L);
        when(row.getInt("attempt_no")).thenReturn(1);
        when(row.getInt("max_attempts")).thenReturn(3);
        when(row.getLong("version")).thenReturn(4L);
        doAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(row, 0));
        }).when(jdbc).query(anyString(), any(RowMapper.class), eq(Timestamp.from(NOW)), eq(50));
    }

    @Test
    void versionConflictSkipsAllAttemptHistoryAndOutboxWrites() {
        assertThat(service.recoverBatch()).isEqualTo(new RecoveryBatchResult(1, 0, 0, 0, 1));
        verify(jdbc).query(anyString(), any(RowMapper.class), eq(Timestamp.from(NOW)), eq(50));
        verify(jdbc).update(anyString(), eq("QUEUED"), eq(Timestamp.from(NOW)), eq(42L), eq(4L), eq(false));
        verifyNoMoreInteractions(jdbc);
    }

    @Test
    void unexpectedAttemptUpdateFailureAbortsTheTransaction() {
        when(jdbc.update(anyString(), eq("QUEUED"), eq(Timestamp.from(NOW)), eq(42L), eq(4L), eq(false)))
                .thenReturn(1);
        assertThatThrownBy(service::recoverBatch).isInstanceOf(IllegalStateException.class)
                .hasMessage("Recovery could not update exactly one attempt");
        verify(jdbc).query(anyString(), any(RowMapper.class), eq(Timestamp.from(NOW)), eq(50));
        verify(jdbc).update(anyString(), eq("QUEUED"), eq(Timestamp.from(NOW)), eq(42L), eq(4L), eq(false));
        verify(jdbc).update(anyString(), eq(Timestamp.from(NOW)), eq(9L), eq(Timestamp.from(NOW)));
        verifyNoMoreInteractions(jdbc);
    }
}
