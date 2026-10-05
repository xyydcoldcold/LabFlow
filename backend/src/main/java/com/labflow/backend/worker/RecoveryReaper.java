package com.labflow.backend.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "labflow.recovery.reaper",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class RecoveryReaper {

    private static final Logger logger = LoggerFactory.getLogger(RecoveryReaper.class);
    private final RecoveryBatchService batchService;

    public RecoveryReaper(RecoveryBatchService batchService) {
        this.batchService = batchService;
    }

    @Scheduled(
            fixedDelayString = "${labflow.recovery.reaper.fixed-delay:PT5S}",
            initialDelayString = "${labflow.recovery.reaper.initial-delay:PT5S}"
    )
    public void recoverExpiredAttempts() {
        try {
            RecoveryBatchResult result = batchService.recoverBatch();
            if (result.claimed() > 0) {
                logger.info("Expired attempts: {} claimed, {} requeued, {} exhausted, {} cancelled, {} skipped",
                        result.claimed(), result.requeued(), result.exhausted(), result.cancelled(), result.skipped());
            }
        } catch (RuntimeException exception) {
            logger.error("Attempt recovery batch failed; transaction rolled back", exception);
        }
    }
}
