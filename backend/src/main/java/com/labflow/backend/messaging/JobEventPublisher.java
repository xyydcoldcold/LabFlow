package com.labflow.backend.messaging;

public interface JobEventPublisher {

    void publish(JobQueuedMessage message);

    default void publishTo(JobQueuedMessage message, String routingKey) {
        throw new UnsupportedOperationException("Publisher does not support routed dispatch");
    }
}
