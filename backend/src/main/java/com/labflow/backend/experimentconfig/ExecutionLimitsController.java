package com.labflow.backend.experimentconfig;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exposes the server-side limits so the config form can use the same bounds. */
@RestController
public class ExecutionLimitsController {

    private final ExperimentConfigValidator validator;

    public ExecutionLimitsController(ExperimentConfigValidator validator) {
        this.validator = validator;
    }

    @GetMapping("/api/experiment-configs/limits")
    public ExecutionLimits limits() {
        return validator.limits();
    }
}
