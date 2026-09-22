package com.labflow.backend.worker;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

public record RegisterWorkerRequest(
        @NotBlank @Size(max = 200) String instanceName,
        @NotBlank @Size(max = 255) String imageDigest,
        @NotEmpty @Size(max = 20) List<@NotBlank @Size(max = 100) String> capabilities
) {
}
