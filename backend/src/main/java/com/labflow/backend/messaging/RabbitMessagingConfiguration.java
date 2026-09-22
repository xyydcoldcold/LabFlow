package com.labflow.backend.messaging;

import java.util.List;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMessagingConfiguration {

    @Bean
    Declarables jobTopology() {
        DirectExchange jobsExchange = new DirectExchange(RabbitTopology.JOBS_EXCHANGE, true, false);
        DirectExchange deadLetterExchange = new DirectExchange(RabbitTopology.JOBS_DLX, true, false);

        Queue jobsQueue = QueueBuilder.durable(RabbitTopology.JOBS_QUEUE)
                .quorum()
                .deadLetterExchange(RabbitTopology.JOBS_DLX)
                .deadLetterRoutingKey(RabbitTopology.JOBS_DEAD_ROUTING_KEY)
                .build();
        Queue retry15Seconds = retryQueue(RabbitTopology.RETRY_15S_QUEUE, 15_000);
        Queue retry60Seconds = retryQueue(RabbitTopology.RETRY_60S_QUEUE, 60_000);
        Queue retry300Seconds = retryQueue(RabbitTopology.RETRY_300S_QUEUE, 300_000);
        Queue deadLetterQueue = QueueBuilder.durable(RabbitTopology.JOBS_DLQ).build();

        List<Declarable> declarables = List.of(
                jobsExchange,
                deadLetterExchange,
                jobsQueue,
                retry15Seconds,
                retry60Seconds,
                retry300Seconds,
                deadLetterQueue,
                binding(jobsQueue, jobsExchange, RabbitTopology.JOBS_ROUTING_KEY),
                binding(retry15Seconds, jobsExchange, RabbitTopology.RETRY_15S_ROUTING_KEY),
                binding(retry60Seconds, jobsExchange, RabbitTopology.RETRY_60S_ROUTING_KEY),
                binding(retry300Seconds, jobsExchange, RabbitTopology.RETRY_300S_ROUTING_KEY),
                binding(deadLetterQueue, deadLetterExchange, RabbitTopology.JOBS_DEAD_ROUTING_KEY)
        );
        return new Declarables(declarables);
    }

    private Queue retryQueue(String name, int ttlMilliseconds) {
        return QueueBuilder.durable(name)
                .ttl(ttlMilliseconds)
                .deadLetterExchange(RabbitTopology.JOBS_EXCHANGE)
                .deadLetterRoutingKey(RabbitTopology.JOBS_ROUTING_KEY)
                .build();
    }

    private Binding binding(Queue queue, DirectExchange exchange, String routingKey) {
        return BindingBuilder.bind(queue).to(exchange).with(routingKey);
    }
}
