package com.labflow.backend.experimentconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

class ExperimentConfigValidatorTest {

    private final ExperimentConfigValidator validator = new ExperimentConfigValidator();

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "1", "\"config\"", "true"})
    void rejectsNonObjectSpecifications(String json) {
        assertThatThrownBy(() -> validator.validate(new ObjectMapper().readTree(json)))
                .isInstanceOf(InvalidExperimentConfigException.class)
                .hasMessageContaining("JSON object");
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "\"300\"", "300.5", "true", "2147483648", "-2147483649"})
    void rejectsNonIntegerAndOverflowingResourceLimits(String json) {
        ObjectNode spec = validSpec();
        spec.set("timeoutSeconds", new ObjectMapper().readTree(json));
        assertThatThrownBy(() -> validator.validate(spec))
                .isInstanceOf(InvalidExperimentConfigException.class)
                .hasMessageContaining("timeoutSeconds must be an integer");
    }

    @Test
    void acceptsInclusiveSchemaLimitsAndRejectsTheirNeighbors() {
        assertThat(validator.validate(validSpec().put("charge", -20).put("spin", 0)
                .put("maxMemoryMb", 128).put("timeoutSeconds", 1))).isNotNull();
        assertThat(validator.validate(validSpec().put("charge", 20).put("spin", 20)
                .put("maxMemoryMb", 65536).put("timeoutSeconds", 86400))).isNotNull();
        for (ObjectNode invalid : new ObjectNode[] {validSpec().put("charge", -21),
                validSpec().put("charge", 21), validSpec().put("spin", -1), validSpec().put("spin", 21),
                validSpec().put("maxMemoryMb", 127), validSpec().put("maxMemoryMb", 65537),
                validSpec().put("timeoutSeconds", 86401)}) {
            assertThatThrownBy(() -> validator.validate(invalid)).isInstanceOf(InvalidExperimentConfigException.class);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "123", "\"\"", "\"   \""})
    void rejectsMissingTextValuesWithoutCoercion(String json) {
        ObjectNode spec = validSpec();
        spec.set("basis", new ObjectMapper().readTree(json));
        assertThatThrownBy(() -> validator.validate(spec))
                .isInstanceOf(InvalidExperimentConfigException.class).hasMessageContaining("basis must be a non-blank string");
    }

    @Test
    void appliesDeploymentLimitsWithinTheSchema() {
        ExperimentConfigValidator limited = new ExperimentConfigValidator(1024, 300);
        assertThat(limited.validate(validSpec())).isNotNull();
        assertThatThrownBy(() -> limited.validate(validSpec().put("maxMemoryMb", 2048)))
                .isInstanceOf(InvalidExperimentConfigException.class);
        assertThatThrownBy(() -> limited.validate(validSpec().put("timeoutSeconds", 301)))
                .isInstanceOf(InvalidExperimentConfigException.class);
        assertThatThrownBy(() -> new ExperimentConfigValidator(64, 300))
                .isInstanceOf(IllegalArgumentException.class);
    }

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

    @Test
    void acceptsOnlyBoundedWhitelistedDemoConfigurations() {
        ObjectNode demo = JsonNodeFactory.instance.objectNode().put("schemaVersion", 1)
                .put("taskType", "demo.sleep_hash").put("sleepSeconds", 3)
                .put("maxMemoryMb", 1024).put("timeoutSeconds", 30);
        assertThat(validator.validate(demo)).isEqualTo(demo);
        assertThatThrownBy(() -> validator.validate(demo.deepCopy().put("sleepSeconds", 31)))
                .isInstanceOf(InvalidExperimentConfigException.class);
        assertThatThrownBy(() -> validator.validate(demo.deepCopy().put("command", "arbitrary")))
                .isInstanceOf(InvalidExperimentConfigException.class);
        assertThatThrownBy(() -> new ExperimentConfigValidator(1024, 300).validate(demo.deepCopy().put("maxMemoryMb", 2048)))
                .isInstanceOf(InvalidExperimentConfigException.class);
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
