package com.labflow.backend.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class RecoveryReaperTest {

    @Test
    void schedulerKeepsRunningAfterABatchFailure() {
        RecoveryBatchService service = mock(RecoveryBatchService.class);
        when(service.recoverBatch()).thenThrow(new IllegalStateException("database temporarily unavailable"))
                .thenReturn(new RecoveryBatchResult(0, 0, 0, 0, 0));
        RecoveryReaper reaper = new RecoveryReaper(service);
        assertThatCode(reaper::recoverExpiredAttempts).doesNotThrowAnyException();
        assertThatCode(reaper::recoverExpiredAttempts).doesNotThrowAnyException();
        verify(service, times(2)).recoverBatch();
    }

    @Test
    void schedulerCanBeDisabled() {
        context().withPropertyValues("labflow.recovery.reaper.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(RecoveryReaper.class));
    }

    @Test
    void schedulerIsEnabledByDefault() {
        context().run(context -> assertThat(context).hasSingleBean(RecoveryReaper.class));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 1001})
    void rejectsInvalidBatchSizes(int batchSize) {
        assertThatThrownBy(() -> new RecoveryProperties(batchSize)).isInstanceOf(IllegalArgumentException.class);
    }

    private ApplicationContextRunner context() {
        return new ApplicationContextRunner()
                .withUserConfiguration(RecoveryReaper.class)
                .withBean(RecoveryBatchService.class, () -> mock(RecoveryBatchService.class));
    }
}
