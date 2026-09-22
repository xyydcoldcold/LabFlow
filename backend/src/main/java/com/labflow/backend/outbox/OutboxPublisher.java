package com.labflow.backend.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "labflow.outbox.publisher",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class OutboxPublisher {

    private static final Logger logger = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxBatchService batchService;

    public OutboxPublisher(OutboxBatchService batchService) {
        this.batchService = batchService;
    }

    @Scheduled(
            fixedDelayString = "${labflow.outbox.publisher.fixed-delay:PT1S}",
            initialDelayString = "${labflow.outbox.publisher.initial-delay:PT2S}"
    )
    public void publishPendingEvents() {
        try {
            OutboxBatchResult result = batchService.publishBatch();
            if (result.claimed() > 0) {
                logger.debug("Processed {} outbox events: {} published, {} failed",
                        result.claimed(), result.published(), result.failed());
            }
        } catch (RuntimeException exception) {
            logger.error("Outbox batch failed before all events could be processed", exception);
        }
    }
}
