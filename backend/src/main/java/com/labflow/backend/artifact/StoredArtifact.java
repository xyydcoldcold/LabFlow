package com.labflow.backend.artifact;

import java.util.Objects;

public record StoredArtifact(String path, long sizeBytes) {

    public StoredArtifact {
        Objects.requireNonNull(path);
        if (path.isBlank()) {
            throw new IllegalArgumentException("Stored artifact path cannot be blank");
        }
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("Stored artifact size cannot be negative");
        }
    }
}
