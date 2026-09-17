package com.labflow.backend.experimentconfig;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}/configs")
public class ExperimentConfigController {

    private final ExperimentConfigService configService;

    public ExperimentConfigController(ExperimentConfigService configService) {
        this.configService = configService;
    }

    @PostMapping
    public ResponseEntity<ExperimentConfigResponse> createVersion(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long projectId,
            @Valid @RequestBody CreateExperimentConfigRequest request
    ) {
        ExperimentConfig config = configService.createVersion(
                projectId,
                currentUserId(jwt),
                request.name(),
                request.spec()
        );
        URI location = URI.create("/api/projects/" + projectId + "/configs/" + config.getId());
        return ResponseEntity.created(location).body(ExperimentConfigResponse.from(config));
    }

    @GetMapping
    public List<ExperimentConfigResponse> list(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long projectId
    ) {
        return configService.list(projectId, currentUserId(jwt)).stream()
                .map(ExperimentConfigResponse::from)
                .toList();
    }

    @GetMapping("/{configId}")
    public ExperimentConfigResponse get(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long projectId,
            @PathVariable long configId
    ) {
        return ExperimentConfigResponse.from(
                configService.get(projectId, configId, currentUserId(jwt))
        );
    }

    private long currentUserId(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }
}
