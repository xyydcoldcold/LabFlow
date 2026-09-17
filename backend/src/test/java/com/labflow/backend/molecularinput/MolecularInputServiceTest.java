package com.labflow.backend.molecularinput;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import com.labflow.backend.artifact.ArtifactStorage;
import com.labflow.backend.artifact.ArtifactStorageProperties;
import com.labflow.backend.artifact.StoredArtifact;
import com.labflow.backend.project.Project;
import com.labflow.backend.project.ProjectAccessDeniedException;
import com.labflow.backend.project.ProjectPermissionService;
import com.labflow.backend.project.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.util.unit.DataSize;

@ExtendWith(MockitoExtension.class)
class MolecularInputServiceTest {

    private static final long PROJECT_ID = 10L;
    private static final long USER_ID = 20L;
    private static final long INPUT_ID = 30L;
    private static final Instant NOW = Instant.parse("2026-09-15T12:00:00Z");
    private static final String SHA256 = "ac320214c0999aeaf989356386836df037c824731536d6b9ac6dcb5e1fd66c45";

    @Mock
    private MolecularInputRepository molecularInputRepository;

    @Mock
    private MolecularInputPersistenceService persistenceService;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private ProjectPermissionService permissionService;

    @Mock
    private ArtifactStorage artifactStorage;

    private MolecularInputService service;

    @BeforeEach
    void setUp() {
        MolecularInputValidator validator = new MolecularInputValidator(new ArtifactStorageProperties(
                Path.of("artifacts"),
                DataSize.ofMegabytes(1)
        ));
        service = new MolecularInputService(
                molecularInputRepository,
                persistenceService,
                projectRepository,
                permissionService,
                validator,
                artifactStorage,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void uploadsValidatedContentForAContributor() throws Exception {
        byte[] content = validXyz();
        Project project = project();
        StoredArtifact artifact = new StoredArtifact("artifact-id", content.length);
        MolecularInput created = molecularInput(project, artifact.path(), content.length);
        when(molecularInputRepository.findByProject_IdAndSha256(PROJECT_ID, SHA256))
                .thenReturn(Optional.empty());
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(artifactStorage.store(any(InputStream.class))).thenReturn(artifact);
        when(persistenceService.create(
                eq(project),
                any(ValidatedMolecularInput.class),
                eq(SHA256),
                eq(artifact),
                eq(NOW)
        )).thenReturn(created);

        MolecularInputUploadResult result = service.upload(PROJECT_ID, USER_ID, " hydrogen.xyz ", content);

        assertThat(result.input()).isSameAs(created);
        assertThat(result.created()).isTrue();
        verify(permissionService).requireContribute(PROJECT_ID, USER_ID);
        verify(artifactStorage).store(any(InputStream.class));
    }

    @Test
    void returnsAnExistingRecordWithoutWritingAnotherArtifact() {
        MolecularInput existing = molecularInput(project(), "existing-artifact", validXyz().length);
        when(molecularInputRepository.findByProject_IdAndSha256(PROJECT_ID, SHA256))
                .thenReturn(Optional.of(existing));

        MolecularInputUploadResult result = service.upload(PROJECT_ID, USER_ID, "hydrogen.xyz", validXyz());

        assertThat(result.input()).isSameAs(existing);
        assertThat(result.created()).isFalse();
        verifyNoInteractions(projectRepository, artifactStorage, persistenceService);
    }

    @Test
    void deniesUploadBeforeValidatingOrStoringContent() {
        when(permissionService.requireContribute(PROJECT_ID, USER_ID))
                .thenThrow(new ProjectAccessDeniedException(PROJECT_ID));

        assertThatThrownBy(() -> service.upload(PROJECT_ID, USER_ID, "hydrogen.xyz", validXyz()))
                .isInstanceOf(ProjectAccessDeniedException.class);

        verifyNoInteractions(molecularInputRepository, projectRepository, artifactStorage, persistenceService);
    }

    @Test
    void cleansUpTheLosingArtifactAndReturnsConcurrentDuplicate() {
        byte[] content = validXyz();
        Project project = project();
        StoredArtifact artifact = new StoredArtifact("losing-artifact", content.length);
        MolecularInput winner = molecularInput(project, "winning-artifact", content.length);
        when(molecularInputRepository.findByProject_IdAndSha256(PROJECT_ID, SHA256))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(artifactStorage.store(any(InputStream.class))).thenReturn(artifact);
        when(persistenceService.create(
                any(Project.class),
                any(ValidatedMolecularInput.class),
                anyString(),
                any(StoredArtifact.class),
                any(Instant.class)
        ))
                .thenThrow(new DataIntegrityViolationException("duplicate"));

        MolecularInputUploadResult result = service.upload(PROJECT_ID, USER_ID, "hydrogen.xyz", content);

        assertThat(result.input()).isSameAs(winner);
        assertThat(result.created()).isFalse();
        verify(artifactStorage).delete("losing-artifact");
    }

    @Test
    void cleansUpTheArtifactWhenPersistenceFails() {
        byte[] content = validXyz();
        Project project = project();
        StoredArtifact artifact = new StoredArtifact("failed-artifact", content.length);
        IllegalStateException failure = new IllegalStateException("database unavailable");
        when(molecularInputRepository.findByProject_IdAndSha256(PROJECT_ID, SHA256))
                .thenReturn(Optional.empty());
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(artifactStorage.store(any(InputStream.class))).thenReturn(artifact);
        when(persistenceService.create(
                any(Project.class),
                any(ValidatedMolecularInput.class),
                anyString(),
                any(StoredArtifact.class),
                any(Instant.class)
        )).thenThrow(failure);

        assertThatThrownBy(() -> service.upload(PROJECT_ID, USER_ID, "hydrogen.xyz", content))
                .isSameAs(failure);
        verify(artifactStorage).delete("failed-artifact");
    }

    @Test
    void listsInputsOnlyAfterCheckingProjectVisibility() {
        MolecularInput input = molecularInput(project(), "artifact-id", validXyz().length);
        when(molecularInputRepository.findAllByProject_IdOrderByCreatedAtDesc(PROJECT_ID))
                .thenReturn(List.of(input));

        assertThat(service.list(PROJECT_ID, USER_ID)).containsExactly(input);
        verify(permissionService).requireView(PROJECT_ID, USER_ID);
    }

    @Test
    void getsAnInputThroughAProjectScopedQuery() {
        MolecularInput input = molecularInput(project(), "artifact-id", validXyz().length);
        when(molecularInputRepository.findByIdAndProject_Id(INPUT_ID, PROJECT_ID))
                .thenReturn(Optional.of(input));

        assertThat(service.get(PROJECT_ID, INPUT_ID, USER_ID)).isSameAs(input);
        verify(permissionService).requireView(PROJECT_ID, USER_ID);
        verify(molecularInputRepository, never()).findById(INPUT_ID);
    }

    @Test
    void reportsAnInputFromAnotherProjectAsNotFound() {
        when(molecularInputRepository.findByIdAndProject_Id(INPUT_ID, PROJECT_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(PROJECT_ID, INPUT_ID, USER_ID))
                .isInstanceOf(MolecularInputNotFoundException.class);
    }

    private Project project() {
        return org.mockito.Mockito.mock(Project.class);
    }

    private MolecularInput molecularInput(Project project, String artifactPath, long sizeBytes) {
        when(project.getId()).thenReturn(PROJECT_ID);
        return new MolecularInput(project, "hydrogen.xyz", SHA256, artifactPath, sizeBytes, NOW);
    }

    private byte[] validXyz() {
        return "2\nHydrogen molecule\nH 0 0 0\nH 0 0 0.74\n".getBytes(StandardCharsets.UTF_8);
    }
}
