package com.labflow.backend.artifact;

public class ArtifactStorageException extends RuntimeException {

    public ArtifactStorageException(String message) {
        super(message);
    }

    public ArtifactStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
