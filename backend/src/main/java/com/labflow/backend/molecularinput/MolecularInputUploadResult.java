package com.labflow.backend.molecularinput;

import java.util.Objects;

public record MolecularInputUploadResult(MolecularInput input, boolean created) {

    public MolecularInputUploadResult {
        Objects.requireNonNull(input);
    }
}
