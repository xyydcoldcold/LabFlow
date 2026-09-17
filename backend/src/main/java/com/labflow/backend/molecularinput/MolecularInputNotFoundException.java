package com.labflow.backend.molecularinput;

public class MolecularInputNotFoundException extends RuntimeException {

    public MolecularInputNotFoundException(long projectId, long inputId) {
        super("Molecular input " + inputId + " was not found in project " + projectId);
    }
}
