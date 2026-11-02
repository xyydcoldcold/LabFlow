package com.labflow.backend.messaging;

import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Declares the job exchanges and queues when the backend starts.
 *
 * <p>Spring AMQP otherwise declares them only when the backend first opens a broker
 * connection, which happens on the first published job. Until then the worker cannot
 * consume from {@link RabbitTopology#JOBS_QUEUE} and exits on startup.
 */
@Component
@ConditionalOnProperty(
        prefix = "labflow.messaging",
        name = "declare-topology-on-startup",
        havingValue = "true"
)
class RabbitTopologyInitializer implements ApplicationRunner {

    private final RabbitAdmin rabbitAdmin;

    RabbitTopologyInitializer(RabbitAdmin rabbitAdmin) {
        this.rabbitAdmin = rabbitAdmin;
    }

    @Override
    public void run(ApplicationArguments args) {
        rabbitAdmin.initialize();
    }
}
