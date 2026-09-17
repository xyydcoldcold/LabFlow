package com.labflow.backend.molecularinput;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import com.labflow.backend.artifact.ArtifactStorageProperties;
import org.springframework.stereotype.Component;

@Component
public class MolecularInputValidator {

    private static final int MAX_FILENAME_LENGTH = 255;
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("xyz");
    private static final Pattern ELEMENT_SYMBOL = Pattern.compile("[A-Z][a-z]?");

    private final long maximumSizeBytes;

    public MolecularInputValidator(ArtifactStorageProperties storageProperties) {
        this.maximumSizeBytes = storageProperties.maxUploadSize().toBytes();
    }

    ValidatedMolecularInput validate(String originalFilename, byte[] content) {
        String safeFilename = validateFilename(originalFilename);
        if (content == null || content.length == 0) {
            throw new InvalidMolecularInputException("Molecular input cannot be empty");
        }
        if (content.length > maximumSizeBytes) {
            throw new MolecularInputTooLargeException(maximumSizeBytes);
        }

        validateXyzContent(decodeUtf8(content));
        return new ValidatedMolecularInput(safeFilename, content);
    }

    private String validateFilename(String originalFilename) {
        if (originalFilename == null) {
            throw new InvalidMolecularInputException("Molecular input filename is required");
        }

        String filename = originalFilename.trim();
        if (filename.isEmpty() || filename.length() > MAX_FILENAME_LENGTH) {
            throw new InvalidMolecularInputException("Molecular input filename must contain between 1 and 255 characters");
        }
        if (filename.equals(".")
                || filename.equals("..")
                || filename.indexOf('/') >= 0
                || filename.indexOf('\\') >= 0
                || filename.indexOf('\0') >= 0) {
            throw new InvalidMolecularInputException("Molecular input filename must not contain a path");
        }

        int extensionSeparator = filename.lastIndexOf('.');
        if (extensionSeparator <= 0 || extensionSeparator == filename.length() - 1) {
            throw new InvalidMolecularInputException("Molecular input filename must use an allowed extension: .xyz");
        }
        String extension = filename.substring(extensionSeparator + 1).toLowerCase(Locale.ROOT);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new InvalidMolecularInputException("Molecular input filename must use an allowed extension: .xyz");
        }
        return filename;
    }

    private String decodeUtf8(byte[] content) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new InvalidMolecularInputException("Molecular input must be valid UTF-8 text", exception);
        }
    }

    private void validateXyzContent(String text) {
        List<String> lines = text.lines().toList();
        if (lines.size() < 3) {
            throw new InvalidMolecularInputException("XYZ input must include an atom count, comment line, and atoms");
        }

        int atomCount;
        try {
            atomCount = Integer.parseInt(lines.getFirst().trim());
        } catch (NumberFormatException exception) {
            throw new InvalidMolecularInputException("The first XYZ line must contain a positive atom count", exception);
        }
        if (atomCount <= 0) {
            throw new InvalidMolecularInputException("The first XYZ line must contain a positive atom count");
        }
        if (lines.size() < atomCount + 2) {
            throw new InvalidMolecularInputException("XYZ input contains fewer atom rows than declared");
        }

        for (int index = 0; index < atomCount; index++) {
            validateAtomLine(lines.get(index + 2), index + 1);
        }
        for (int index = atomCount + 2; index < lines.size(); index++) {
            if (!lines.get(index).isBlank()) {
                throw new InvalidMolecularInputException("XYZ input contains more atom rows than declared");
            }
        }
    }

    private void validateAtomLine(String line, int atomNumber) {
        String[] fields = line.trim().split("\\s+");
        if (fields.length != 4 || !ELEMENT_SYMBOL.matcher(fields[0]).matches()) {
            throw new InvalidMolecularInputException("XYZ atom row " + atomNumber + " must contain an element and three coordinates");
        }

        for (int index = 1; index < fields.length; index++) {
            try {
                double coordinate = Double.parseDouble(fields[index]);
                if (!Double.isFinite(coordinate)) {
                    throw new NumberFormatException("Coordinate is not finite");
                }
            } catch (NumberFormatException exception) {
                throw new InvalidMolecularInputException(
                        "XYZ atom row " + atomNumber + " contains an invalid coordinate",
                        exception
                );
            }
        }
    }
}
