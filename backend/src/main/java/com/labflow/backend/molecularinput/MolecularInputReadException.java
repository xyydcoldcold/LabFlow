package com.labflow.backend.molecularinput;

public class MolecularInputReadException extends RuntimeException {

    public MolecularInputReadException(Throwable cause) {
        super("Could not read uploaded molecular input", cause);
    }
}
