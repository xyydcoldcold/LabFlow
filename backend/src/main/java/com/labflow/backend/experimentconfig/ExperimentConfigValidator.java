package com.labflow.backend.experimentconfig;

import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.JsonNode;

@Component
public class ExperimentConfigValidator {

    private static final Set<String> FIELDS = Set.of(
            "schemaVersion", "taskType", "method", "basis", "charge", "spin",
            "maxMemoryMb", "timeoutSeconds"
    );

    private final int maxMemoryMb;
    private final int maxTimeoutSeconds;

    public ExperimentConfigValidator() {
        this(65_536, 86_400);
    }

    @Autowired
    public ExperimentConfigValidator(
            @Value("${labflow.execution.max-memory-mb:65536}") int maxMemoryMb,
            @Value("${labflow.execution.max-timeout-seconds:86400}") int maxTimeoutSeconds
    ) {
        if (maxMemoryMb < 128 || maxMemoryMb > 65_536
                || maxTimeoutSeconds < 1 || maxTimeoutSeconds > 86_400) {
            throw new IllegalArgumentException("Execution limits must fit the version 1 schema");
        }
        this.maxMemoryMb = maxMemoryMb;
        this.maxTimeoutSeconds = maxTimeoutSeconds;
    }

    public JsonNode validate(JsonNode spec) {
        if (spec == null || !spec.isObject()) {
            throw invalid("spec must be a JSON object");
        }

        if ("demo.sleep_hash".equals(spec.path("taskType").asString(""))) {
            Set<String> fields = Set.of("schemaVersion", "taskType", "sleepSeconds", "maxMemoryMb", "timeoutSeconds");
            for (String field : spec.propertyNames()) if (!fields.contains(field)) throw invalid("unsupported field: " + field);
            for (String field : fields) if (!spec.has(field)) throw invalid("missing required field: " + field);
            requireInteger(spec, "schemaVersion", 1, 1);
            requireInteger(spec, "maxMemoryMb", 128, maxMemoryMb);
            requireInteger(spec, "timeoutSeconds", 1, maxTimeoutSeconds);
            if (!spec.get("sleepSeconds").isNumber() || !Double.isFinite(spec.get("sleepSeconds").doubleValue())
                    || spec.get("sleepSeconds").doubleValue() < 0 || spec.get("sleepSeconds").doubleValue() > 30)
                throw invalid("sleepSeconds must be between 0 and 30");
            return spec.deepCopy();
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
        requireInteger(spec, "maxMemoryMb", 128, maxMemoryMb);
        requireInteger(spec, "timeoutSeconds", 1, maxTimeoutSeconds);

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
