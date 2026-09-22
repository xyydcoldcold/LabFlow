package com.labflow.backend.messaging;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RabbitJobEventPublisher implements JobEventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final Duration confirmTimeout;

    public RabbitJobEventPublisher(
            RabbitTemplate rabbitTemplate,
            @Value("${labflow.outbox.confirm-timeout:PT5S}") Duration confirmTimeout
    ) {
        this.rabbitTemplate = rabbitTemplate;
        this.confirmTimeout = confirmTimeout;
    }

    @Override
    public void publish(JobQueuedMessage jobMessage) {
        Message message = MessageBuilder.withBody(jobMessage.toJsonBytes())
                .setContentType("application/json")
                .setContentEncoding("UTF-8")
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setMessageId(Long.toString(jobMessage.eventId()))
                .build();
        CorrelationData correlation = new CorrelationData(Long.toString(jobMessage.eventId()));
        rabbitTemplate.send(
                RabbitTopology.JOBS_EXCHANGE,
                RabbitTopology.JOBS_ROUTING_KEY,
                message,
                correlation
        );

        try {
            CorrelationData.Confirm confirm = correlation.getFuture().get(
                    confirmTimeout.toMillis(), TimeUnit.MILLISECONDS
            );
            if (!confirm.ack()) {
                throw new OutboxPublishException("RabbitMQ negatively acknowledged event "
                        + jobMessage.eventId() + ": " + confirm.reason());
            }
            if (correlation.getReturned() != null) {
                throw new OutboxPublishException("RabbitMQ could not route event "
                        + jobMessage.eventId() + ": " + correlation.getReturned().getReplyText());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new OutboxPublishException("Interrupted while confirming event " + jobMessage.eventId(), exception);
        } catch (ExecutionException | TimeoutException | AmqpException exception) {
            throw new OutboxPublishException("Could not confirm event " + jobMessage.eventId(), exception);
        }
    }
}
