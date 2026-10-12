package com.labflow.backend.observability;

import java.util.Map;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Process-local counters and logs describe committed operations, never rolled-back work. */
@Component
public class JobTelemetry {
    public enum Event { SUBMITTED, CLAIMED, SUCCEEDED, FAILED, CANCELLED, CANCEL_REQUESTED, RECOVERED, EXHAUSTED }
    private static final Logger log = LoggerFactory.getLogger(JobTelemetry.class);
    private final MeterRegistry registry;

    public JobTelemetry(MeterRegistry registry) {
        this.registry = registry;
        for (Event event : Event.values()) registry.counter("labflow.job.events", "event", event.name());
    }

    public void committed(Event event, long jobId, Long attemptId, Long workerId) {
        String requestId = MDC.get("requestId");
        Runnable record = () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            try {
                if (requestId == null) MDC.remove("requestId"); else MDC.put("requestId", requestId);
                registry.counter("labflow.job.events", "event", event.name()).increment();
                log.atInfo().addKeyValue("event", event.name()).addKeyValue("jobId", jobId)
                        .addKeyValue("attemptId", attemptId).addKeyValue("workerId", workerId)
                        .log("Job lifecycle event committed");
            } catch (RuntimeException failure) {
                // Telemetry failure must not change the outcome of already committed scientific work.
                log.warn("Could not record job telemetry", failure);
            } finally {
                if (previous == null) MDC.clear(); else MDC.setContextMap(previous);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { record.run(); }
            });
        } else record.run();
    }
}
