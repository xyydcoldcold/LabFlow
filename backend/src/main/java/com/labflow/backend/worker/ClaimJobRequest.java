package com.labflow.backend.worker;

import jakarta.validation.constraints.Positive;

public record ClaimJobRequest(@Positive long workerId) {
}
