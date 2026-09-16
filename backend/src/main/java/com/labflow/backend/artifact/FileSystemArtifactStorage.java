package com.labflow.backend.artifact;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.UUID;

final class FileSystemArtifactStorage implements ArtifactStorage {

    private final Path root;

    FileSystemArtifactStorage(Path root) {
        this.root = Objects.requireNonNull(root).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException exception) {
            throw new ArtifactStorageException("Could not initialize artifact storage", exception);
        }
    }

    @Override
    public StoredArtifact store(InputStream content) {
        Objects.requireNonNull(content);
        Path temporaryPath = null;
        try {
            temporaryPath = Files.createTempFile(root, ".upload-", ".tmp");
            long sizeBytes = Files.copy(content, temporaryPath, StandardCopyOption.REPLACE_EXISTING);
            String artifactPath = UUID.randomUUID().toString();
            Path finalPath = resolveSafely(artifactPath);
            moveIntoPlace(temporaryPath, finalPath);
            return new StoredArtifact(artifactPath, sizeBytes);
        } catch (IOException exception) {
            deleteTemporaryFile(temporaryPath);
            throw new ArtifactStorageException("Could not store artifact", exception);
        }
    }

    @Override
    public InputStream open(String artifactPath) {
        try {
            return Files.newInputStream(resolveSafely(artifactPath));
        } catch (IOException exception) {
            throw new ArtifactStorageException("Could not open artifact", exception);
        }
    }

    @Override
    public void delete(String artifactPath) {
        try {
            Files.deleteIfExists(resolveSafely(artifactPath));
        } catch (IOException exception) {
            throw new ArtifactStorageException("Could not delete artifact", exception);
        }
    }

    private Path resolveSafely(String artifactPath) {
        Objects.requireNonNull(artifactPath);
        if (artifactPath.isBlank()) {
            throw new IllegalArgumentException("Artifact path cannot be blank");
        }

        Path relativePath = Path.of(artifactPath);
        if (relativePath.isAbsolute()) {
            throw new IllegalArgumentException("Artifact path must be relative");
        }

        Path resolvedPath = root.resolve(relativePath).normalize();
        if (!resolvedPath.startsWith(root) || resolvedPath.equals(root)) {
            throw new IllegalArgumentException("Artifact path escapes the storage root");
        }
        return resolvedPath;
    }

    private void moveIntoPlace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private void deleteTemporaryFile(Path temporaryPath) {
        if (temporaryPath == null) {
            return;
        }
        try {
            Files.deleteIfExists(temporaryPath);
        } catch (IOException ignored) {
            // Preserve the original storage failure.
        }
    }
}
