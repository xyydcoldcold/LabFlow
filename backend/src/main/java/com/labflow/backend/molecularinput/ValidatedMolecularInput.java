package com.labflow.backend.molecularinput;

import java.util.Objects;

record ValidatedMolecularInput(String originalFilename, byte[] content) {

    ValidatedMolecularInput {
        Objects.requireNonNull(originalFilename);
        Objects.requireNonNull(content);
        content = content.clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }

    long sizeBytes() {
        return content.length;
    }
}
