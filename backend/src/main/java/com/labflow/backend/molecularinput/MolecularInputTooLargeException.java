package com.labflow.backend.molecularinput;

public class MolecularInputTooLargeException extends RuntimeException {

    public MolecularInputTooLargeException(long maximumSizeBytes) {
        super("Molecular input exceeds the maximum size of " + maximumSizeBytes + " bytes");
    }
}
