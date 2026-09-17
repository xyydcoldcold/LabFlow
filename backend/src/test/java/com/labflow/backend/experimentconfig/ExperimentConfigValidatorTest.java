package com.labflow.backend.experimentconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

class ExperimentConfigValidatorTest {

    private final ExperimentConfigValidator validator = new ExperimentConfigValidator();

    @Test
    void acceptsAndCopiesAValidV1SinglePointConfig() {
        ObjectNode original = validSpec();

        JsonNode validated = validator.validate(original);
        original.put("method", "UHF");

        assertThat(validated.get("schemaVersion").intValue()).isEqualTo(1);
        assertThat(validated.get("method").stringValue()).isEqualTo("RHF");
    }

    @Test
    void rejectsMissingUnknownAndOutOfRangeFields() {
        assertThatThrownBy(() -> validator.validate(validSpec().without("basis")))
                .isInstanceOf(InvalidExperimentConfigException.class)
                .hasMessageContaining("missing required field: basis");

        assertThatThrownBy(() -> validator.validate(validSpec().put("command", "rm -rf /")))
                .isInstanceOf(InvalidExperimentConfigException.class)
                .hasMessageContaining("unsupported field: command");

        assertThatThrownBy(() -> validator.validate(validSpec().put("timeoutSeconds", 0)))
                .isInstanceOf(InvalidExperimentConfigException.class)
                .hasMessageContaining("timeoutSeconds must be between 1 and 86400");
    }

    @Test
    void rejectsUnsupportedSchemaAndTaskTypes() {
        assertThatThrownBy(() -> validator.validate(validSpec().put("schemaVersion", 2)))
                .isInstanceOf(InvalidExperimentConfigException.class)
                .hasMessageContaining("schemaVersion must be between 1 and 1");

        assertThatThrownBy(() -> validator.validate(validSpec().put("taskType", "shell.exec")))
                .isInstanceOf(InvalidExperimentConfigException.class)
                .hasMessageContaining("taskType must be pyscf.single_point");
    }

    private ObjectNode validSpec() {
        return JsonNodeFactory.instance.objectNode()
                .put("schemaVersion", 1)
                .put("taskType", "pyscf.single_point")
                .put("method", "RHF")
                .put("basis", "sto-3g")
                .put("charge", 0)
                .put("spin", 0)
                .put("maxMemoryMb", 1024)
                .put("timeoutSeconds", 300);
    }
}
