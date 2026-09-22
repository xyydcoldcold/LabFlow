package com.labflow.backend.worker;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record AppendLogRequest(
        @PositiveOrZero long seqNo,
        @NotBlank String stream,
        @NotBlank @Size(max = 16_384) String content,
        @NotNull Instant emittedAt
) {
}
