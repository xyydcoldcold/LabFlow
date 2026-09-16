package com.labflow.backend.artifact;

import java.nio.file.Path;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties(prefix = "labflow.artifacts")
public record ArtifactStorageProperties(
        Path root,
        DataSize maxUploadSize
) {
    public ArtifactStorageProperties {
        Objects.requireNonNull(root, "Artifact root is required");
        Objects.requireNonNull(maxUploadSize, "Artifact maximum upload size is required");
        if (maxUploadSize.isNegative() || maxUploadSize.toBytes() == 0) {
            throw new IllegalArgumentException("Artifact maximum upload size must be greater than zero");
        }
    }
}
