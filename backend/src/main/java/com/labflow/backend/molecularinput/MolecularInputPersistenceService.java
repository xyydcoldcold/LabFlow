package com.labflow.backend.molecularinput;

import java.time.Instant;

import com.labflow.backend.artifact.StoredArtifact;
import com.labflow.backend.project.Project;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class MolecularInputPersistenceService {

    private final MolecularInputRepository molecularInputRepository;

    MolecularInputPersistenceService(MolecularInputRepository molecularInputRepository) {
        this.molecularInputRepository = molecularInputRepository;
    }

    @Transactional
    MolecularInput create(
            Project project,
            ValidatedMolecularInput upload,
            String sha256,
            StoredArtifact artifact,
            Instant createdAt
    ) {
        MolecularInput molecularInput = new MolecularInput(
                project,
                upload.originalFilename(),
                sha256,
                artifact.path(),
                artifact.sizeBytes(),
                createdAt
        );
        return molecularInputRepository.saveAndFlush(molecularInput);
    }
}
