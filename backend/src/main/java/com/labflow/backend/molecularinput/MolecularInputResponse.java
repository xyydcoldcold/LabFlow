package com.labflow.backend.molecularinput;

import java.time.Instant;

public record MolecularInputResponse(
        long id,
        String originalFilename,
        String sha256,
        long sizeBytes,
        Instant createdAt
) {
    static MolecularInputResponse from(MolecularInput input) {
        return new MolecularInputResponse(
                input.getId(),
                input.getOriginalFilename(),
                input.getSha256(),
                input.getSizeBytes(),
                input.getCreatedAt()
        );
    }
}
