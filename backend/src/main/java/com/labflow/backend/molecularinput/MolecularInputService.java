package com.labflow.backend.molecularinput;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;

import com.labflow.backend.artifact.ArtifactStorage;
import com.labflow.backend.artifact.StoredArtifact;
import com.labflow.backend.project.Project;
import com.labflow.backend.project.ProjectNotFoundException;
import com.labflow.backend.project.ProjectPermissionService;
import com.labflow.backend.project.ProjectRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class MolecularInputService {

    private final MolecularInputRepository molecularInputRepository;
    private final MolecularInputPersistenceService persistenceService;
    private final ProjectRepository projectRepository;
    private final ProjectPermissionService permissionService;
    private final MolecularInputValidator validator;
    private final ArtifactStorage artifactStorage;
    private final Clock clock;

    public MolecularInputService(
            MolecularInputRepository molecularInputRepository,
            MolecularInputPersistenceService persistenceService,
            ProjectRepository projectRepository,
            ProjectPermissionService permissionService,
            MolecularInputValidator validator,
            ArtifactStorage artifactStorage,
            Clock clock
    ) {
        this.molecularInputRepository = molecularInputRepository;
        this.persistenceService = persistenceService;
        this.projectRepository = projectRepository;
        this.permissionService = permissionService;
        this.validator = validator;
        this.artifactStorage = artifactStorage;
        this.clock = clock;
    }

    public MolecularInputUploadResult upload(
            long projectId,
            long currentUserId,
            String originalFilename,
            byte[] content
    ) {
        permissionService.requireContribute(projectId, currentUserId);
        ValidatedMolecularInput upload = validator.validate(originalFilename, content);
        String sha256 = sha256(upload.content());

        return molecularInputRepository.findByProject_IdAndSha256(projectId, sha256)
                .map(existing -> new MolecularInputUploadResult(existing, false))
                .orElseGet(() -> storeNewInput(projectId, upload, sha256));
    }

    public List<MolecularInput> list(long projectId, long currentUserId) {
        permissionService.requireView(projectId, currentUserId);
        return molecularInputRepository.findAllByProject_IdOrderByCreatedAtDesc(projectId);
    }

    public MolecularInput get(long projectId, long inputId, long currentUserId) {
        permissionService.requireView(projectId, currentUserId);
        return molecularInputRepository.findByIdAndProject_Id(inputId, projectId)
                .orElseThrow(() -> new MolecularInputNotFoundException(projectId, inputId));
    }

    private MolecularInputUploadResult storeNewInput(
            long projectId,
            ValidatedMolecularInput upload,
            String sha256
    ) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId));
        StoredArtifact artifact = artifactStorage.store(new ByteArrayInputStream(upload.content()));

        try {
            MolecularInput created = persistenceService.create(project, upload, sha256, artifact, clock.instant());
            return new MolecularInputUploadResult(created, true);
        } catch (DataIntegrityViolationException exception) {
            deleteAfterFailure(artifact.path(), exception);
            MolecularInput existing = molecularInputRepository.findByProject_IdAndSha256(projectId, sha256)
                    .orElseThrow(() -> exception);
            return new MolecularInputUploadResult(existing, false);
        } catch (RuntimeException exception) {
            deleteAfterFailure(artifact.path(), exception);
            throw exception;
        }
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private void deleteAfterFailure(String artifactPath, RuntimeException originalFailure) {
        try {
            artifactStorage.delete(artifactPath);
        } catch (RuntimeException cleanupFailure) {
            originalFailure.addSuppressed(cleanupFailure);
        }
    }
}
