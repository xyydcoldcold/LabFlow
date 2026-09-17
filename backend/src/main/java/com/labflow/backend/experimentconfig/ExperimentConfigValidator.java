package com.labflow.backend.experimentconfig;

import java.util.Set;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class ExperimentConfigValidator {

    private static final Set<String> FIELDS = Set.of(
            "schemaVersion", "taskType", "method", "basis", "charge", "spin",
            "maxMemoryMb", "timeoutSeconds"
    );

    public JsonNode validate(JsonNode spec) {
        if (spec == null || !spec.isObject()) {
            throw invalid("spec must be a JSON object");
        }

        for (String field : spec.propertyNames()) {
            if (!FIELDS.contains(field)) {
                throw invalid("unsupported field: " + field);
            }
        }
        for (String field : FIELDS) {
            if (!spec.has(field)) {
                throw invalid("missing required field: " + field);
            }
        }

        requireInteger(spec, "schemaVersion", 1, 1);
        requireText(spec, "taskType", 100);
        if (!"pyscf.single_point".equals(spec.get("taskType").stringValue())) {
            throw invalid("taskType must be pyscf.single_point");
        }
        requireText(spec, "method", 50);
        requireText(spec, "basis", 100);
        requireInteger(spec, "charge", -20, 20);
        requireInteger(spec, "spin", 0, 20);
        requireInteger(spec, "maxMemoryMb", 128, 65_536);
        requireInteger(spec, "timeoutSeconds", 1, 86_400);

        return spec.deepCopy();
    }

    private void requireText(JsonNode spec, String field, int maxLength) {
        JsonNode value = spec.get(field);
        if (!value.isString() || value.stringValue().isBlank() || value.stringValue().length() > maxLength) {
            throw invalid(field + " must be a non-blank string of at most " + maxLength + " characters");
        }
    }

    private void requireInteger(JsonNode spec, String field, int minimum, int maximum) {
        JsonNode value = spec.get(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalid(field + " must be an integer");
        }
        int number = value.intValue();
        if (number < minimum || number > maximum) {
            throw invalid(field + " must be between " + minimum + " and " + maximum);
        }
    }

    private InvalidExperimentConfigException invalid(String detail) {
        return new InvalidExperimentConfigException("Invalid experiment config: " + detail);
    }
}
