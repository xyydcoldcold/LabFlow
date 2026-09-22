package com.labflow.backend.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;

class RabbitMessagingConfigurationTest {

    @Test
    void declaresDurableWorkRetryAndDeadLetterTopology() {
        var declarables = new RabbitMessagingConfiguration().jobTopology().getDeclarables();

        Queue jobs = queue(declarables, RabbitTopology.JOBS_QUEUE);
        assertThat(jobs.isDurable()).isTrue();
        assertThat(jobs.getArguments())
                .containsEntry("x-queue-type", "quorum")
                .containsEntry("x-dead-letter-exchange", RabbitTopology.JOBS_DLX)
                .containsEntry("x-dead-letter-routing-key", RabbitTopology.JOBS_DEAD_ROUTING_KEY);

        assertRetryQueue(declarables, RabbitTopology.RETRY_15S_QUEUE, 15_000);
        assertRetryQueue(declarables, RabbitTopology.RETRY_60S_QUEUE, 60_000);
        assertRetryQueue(declarables, RabbitTopology.RETRY_300S_QUEUE, 300_000);
        assertThat(queue(declarables, RabbitTopology.JOBS_DLQ).isDurable()).isTrue();

        assertThat(declarables.stream().filter(Binding.class::isInstance).map(Binding.class::cast))
                .anySatisfy(binding -> assertThat(binding.getRoutingKey())
                        .isEqualTo(RabbitTopology.JOBS_ROUTING_KEY))
                .anySatisfy(binding -> assertThat(binding.getRoutingKey())
                        .isEqualTo(RabbitTopology.JOBS_DEAD_ROUTING_KEY));
    }

    private void assertRetryQueue(Iterable<?> declarables, String name, int ttl) {
        Queue queue = queue(declarables, name);
        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.getArguments())
                .containsEntry("x-message-ttl", ttl)
                .containsEntry("x-dead-letter-exchange", RabbitTopology.JOBS_EXCHANGE)
                .containsEntry("x-dead-letter-routing-key", RabbitTopology.JOBS_ROUTING_KEY);
    }

    private Queue queue(Iterable<?> declarables, String name) {
        for (Object declarable : declarables) {
            if (declarable instanceof Queue queue && queue.getName().equals(name)) {
                return queue;
            }
        }
        throw new AssertionError("Queue was not declared: " + name);
    }
}
