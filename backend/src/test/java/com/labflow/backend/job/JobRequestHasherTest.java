package com.labflow.backend.job;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JobRequestHasherTest {

    private final JobRequestHasher hasher = new JobRequestHasher();

    @Test
    void hashesTheSemanticRequestDeterministically() {
        CreateJobRequest request = new CreateJobRequest(11L, 22L, 33L);

        assertThat(hasher.hash(request))
                .hasSize(64)
                .matches("[0-9a-f]{64}")
                .isEqualTo(hasher.hash(new CreateJobRequest(11L, 22L, 33L)))
                .isNotEqualTo(hasher.hash(new CreateJobRequest(11L, 22L, 34L)))
                .isNotEqualTo(hasher.hash(new CreateJobRequest(12L, 22L, 33L)));
    }
}
