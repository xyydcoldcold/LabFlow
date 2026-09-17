package com.labflow.backend.molecularinput;

public class InvalidMolecularInputException extends IllegalArgumentException {

    public InvalidMolecularInputException(String message) {
        super(message);
    }

    public InvalidMolecularInputException(String message, Throwable cause) {
        super(message, cause);
    }
}
