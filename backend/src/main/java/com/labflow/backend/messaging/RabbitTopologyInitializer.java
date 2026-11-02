package com.labflow.backend.messaging;

import org.springframework.amqp.core.AmqpAdmin;
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

    // Boot exposes its RabbitAdmin as an AmqpAdmin bean, so it cannot be injected by its concrete type.
    private final AmqpAdmin amqpAdmin;

    RabbitTopologyInitializer(AmqpAdmin amqpAdmin) {
        this.amqpAdmin = amqpAdmin;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!(amqpAdmin instanceof RabbitAdmin rabbitAdmin)) {
            throw new IllegalStateException("Expected a RabbitAdmin to declare the RabbitMQ topology");
        }
        rabbitAdmin.initialize();
    }
}
