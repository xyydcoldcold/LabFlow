package com.labflow.backend.molecularinput;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.lang.reflect.Constructor;
import java.time.Instant;

import com.labflow.backend.auth.AppUser;
import com.labflow.backend.project.Project;
import org.junit.jupiter.api.Test;

class MolecularInputTest {

    private static final String SHA256 = "a".repeat(64);

    @Test
    void createsMetadataForAPersistedProject() throws Exception {
        Instant now = Instant.parse("2026-09-15T12:00:00Z");
        MolecularInput input = new MolecularInput(
                projectWithId(11L),
                "hydrogen.xyz",
                SHA256,
                "artifact-id",
                42,
                now
        );

        assertThat(input.getProject().getId()).isEqualTo(11L);
        assertThat(input.getOriginalFilename()).isEqualTo("hydrogen.xyz");
        assertThat(input.getSha256()).isEqualTo(SHA256);
        assertThat(input.getArtifactPath()).isEqualTo("artifact-id");
        assertThat(input.getSizeBytes()).isEqualTo(42);
        assertThat(input.getCreatedAt()).isEqualTo(now);
    }

    @Test
    void rejectsInvalidChecksumAndNegativeSize() throws Exception {
        Project project = projectWithId(11L);
        Instant now = Instant.parse("2026-09-15T12:00:00Z");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MolecularInput(project, "hydrogen.xyz", "invalid", "artifact-id", 42, now))
                .withMessageContaining("SHA-256");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MolecularInput(project, "hydrogen.xyz", SHA256, "artifact-id", -1, now))
                .withMessage("Molecular input size cannot be negative");
    }

    private Project projectWithId(Long id) throws Exception {
        Constructor<Project> constructor = Project.class.getDeclaredConstructor(
                Long.class,
                String.class,
                AppUser.class,
                Instant.class,
                Instant.class
        );
        constructor.setAccessible(true);
        Instant now = Instant.parse("2026-09-15T12:00:00Z");
        return constructor.newInstance(id, "Research", appUserWithId(7L), now, now);
    }

    private AppUser appUserWithId(Long id) throws Exception {
        Constructor<AppUser> constructor = AppUser.class.getDeclaredConstructor(
                Long.class,
                String.class,
                String.class,
                String.class,
                Instant.class,
                Instant.class
        );
        constructor.setAccessible(true);
        Instant now = Instant.parse("2026-09-15T12:00:00Z");
        return constructor.newInstance(id, "owner@example.com", "hash", "Owner", now, now);
    }
}
