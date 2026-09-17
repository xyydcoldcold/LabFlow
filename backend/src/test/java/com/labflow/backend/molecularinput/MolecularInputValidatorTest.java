package com.labflow.backend.molecularinput;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import com.labflow.backend.artifact.ArtifactStorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.util.unit.DataSize;

class MolecularInputValidatorTest {

    private MolecularInputValidator validator;

    @BeforeEach
    void setUp() {
        validator = new MolecularInputValidator(new ArtifactStorageProperties(
                Path.of("artifacts"),
                DataSize.ofBytes(128)
        ));
    }

    @Test
    void acceptsAndNormalizesAValidXyzUpload() {
        byte[] content = validXyz();

        ValidatedMolecularInput validated = validator.validate("  hydrogen.XYZ  ", content);

        assertThat(validated.originalFilename()).isEqualTo("hydrogen.XYZ");
        assertThat(validated.content()).isEqualTo(content);
        assertThat(validated.sizeBytes()).isEqualTo(content.length);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "../hydrogen.xyz",
            "folder/hydrogen.xyz",
            "..\\hydrogen.xyz",
            "folder\\hydrogen.xyz"
    })
    void rejectsFilenamesContainingPaths(String filename) {
        assertThatThrownBy(() -> validator.validate(filename, validXyz()))
                .isInstanceOf(InvalidMolecularInputException.class)
                .hasMessage("Molecular input filename must not contain a path");
    }

    @ParameterizedTest
    @ValueSource(strings = {"hydrogen", "hydrogen.mol", ".xyz"})
    void rejectsExtensionsOutsideTheAllowlist(String filename) {
        assertThatThrownBy(() -> validator.validate(filename, validXyz()))
                .isInstanceOf(InvalidMolecularInputException.class)
                .hasMessageContaining("allowed extension");
    }

    @Test
    void rejectsEmptyAndOversizedContent() {
        assertThatThrownBy(() -> validator.validate("hydrogen.xyz", new byte[0]))
                .isInstanceOf(InvalidMolecularInputException.class)
                .hasMessage("Molecular input cannot be empty");
        assertThatThrownBy(() -> validator.validate("hydrogen.xyz", new byte[129]))
                .isInstanceOf(MolecularInputTooLargeException.class)
                .hasMessageContaining("128 bytes");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not-a-count\ncomment\nH 0 0 0",
            "2\ncomment\nH 0 0 0",
            "1\ncomment\nH x 0 0",
            "1\ncomment\nInvalid 0 0 0",
            "1\ncomment\nH 0 0 0\nHe 0 0 1"
    })
    void rejectsMalformedXyzContent(String text) {
        assertThatThrownBy(() -> validator.validate(
                "hydrogen.xyz",
                text.getBytes(StandardCharsets.UTF_8)
        )).isInstanceOf(InvalidMolecularInputException.class);
    }

    @Test
    void rejectsInvalidUtf8() {
        assertThatThrownBy(() -> validator.validate(
                "hydrogen.xyz",
                new byte[] {(byte) 0xc3, (byte) 0x28}
        ))
                .isInstanceOf(InvalidMolecularInputException.class)
                .hasMessage("Molecular input must be valid UTF-8 text");
    }

    private byte[] validXyz() {
        return "2\nHydrogen molecule\nH 0 0 0\nH 0 0 0.74\n".getBytes(StandardCharsets.UTF_8);
    }
}
