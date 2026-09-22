package com.labflow.backend.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

class JobQueuedMessageTest {

    @Test
    void versionOneContractRoundTripsWithOnlyStableIdentifiers() {
        JobQueuedMessage message = new JobQueuedMessage(41L, 73L, 1);

        String json = new String(message.toJsonBytes(), StandardCharsets.UTF_8);

        assertThat(json).isEqualTo("{\"jobId\":41,\"eventId\":73,\"schemaVersion\":1}");
        assertThat(JobQueuedMessage.from(
                JsonNodeFactory.instance.objectNode()
                        .put("schemaVersion", 1)
                        .put("eventId", 73)
                        .put("jobId", 41)
        )).isEqualTo(message);
    }

    @Test
    void rejectsUnknownMissingAndUnsupportedVersionFields() {
        assertThatThrownBy(() -> JobQueuedMessage.from(
                JsonNodeFactory.instance.objectNode()
                        .put("jobId", 41)
                        .put("eventId", 73)
                        .put("schemaVersion", 1)
                        .put("spec", "must-not-cross-the-broker")
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported job message field");

        assertThatThrownBy(() -> JobQueuedMessage.from(
                JsonNodeFactory.instance.objectNode()
                        .put("jobId", 41)
                        .put("schemaVersion", 1)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventId");

        assertThatThrownBy(() -> new JobQueuedMessage(41L, 73L, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schema version: 2");
    }
}
