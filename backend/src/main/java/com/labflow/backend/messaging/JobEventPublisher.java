package com.labflow.backend.messaging;

public interface JobEventPublisher {

    void publish(JobQueuedMessage message);
}
