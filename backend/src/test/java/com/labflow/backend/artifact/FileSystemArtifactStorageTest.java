package com.labflow.backend.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSystemArtifactStorageTest {

    @TempDir
    private Path temporaryDirectory;

    @Test
    void storesContentUnderARandomServerGeneratedPath() throws Exception {
        FileSystemArtifactStorage storage = new FileSystemArtifactStorage(temporaryDirectory);
        byte[] content = "H 0 0 0\nH 0 0 1".getBytes(StandardCharsets.UTF_8);

        StoredArtifact stored = storage.store(new ByteArrayInputStream(content));

        assertThat(stored.path()).doesNotContain("H 0 0 0");
        assertThat(stored.sizeBytes()).isEqualTo(content.length);
        assertThat(Files.readAllBytes(temporaryDirectory.resolve(stored.path()))).isEqualTo(content);
    }

    @Test
    void opensAndDeletesStoredContent() throws Exception {
        FileSystemArtifactStorage storage = new FileSystemArtifactStorage(temporaryDirectory);
        StoredArtifact stored = storage.store(new ByteArrayInputStream("content".getBytes(StandardCharsets.UTF_8)));

        try (var opened = storage.open(stored.path())) {
            assertThat(opened.readAllBytes()).isEqualTo("content".getBytes(StandardCharsets.UTF_8));
        }

        storage.delete(stored.path());

        assertThat(temporaryDirectory.resolve(stored.path())).doesNotExist();
    }

    @Test
    void rejectsPathsThatEscapeTheStorageRoot() {
        FileSystemArtifactStorage storage = new FileSystemArtifactStorage(temporaryDirectory);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> storage.open("../outside"))
                .withMessage("Artifact path escapes the storage root");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> storage.open(temporaryDirectory.resolve("absolute").toString()))
                .withMessage("Artifact path must be relative");
    }
}
