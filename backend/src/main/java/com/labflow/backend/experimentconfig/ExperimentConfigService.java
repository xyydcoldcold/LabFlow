package com.labflow.backend.experimentconfig;

import java.time.Clock;
import java.util.List;

import com.labflow.backend.auth.AppUser;
import com.labflow.backend.auth.AppUserRepository;
import com.labflow.backend.project.Project;
import com.labflow.backend.project.ProjectNotFoundException;
import com.labflow.backend.project.ProjectPermissionService;
import com.labflow.backend.project.ProjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Service
public class ExperimentConfigService {

    private final ExperimentConfigRepository configRepository;
    private final ProjectRepository projectRepository;
    private final AppUserRepository userRepository;
    private final ProjectPermissionService permissionService;
    private final ExperimentConfigValidator validator;
    private final Clock clock;

    public ExperimentConfigService(
            ExperimentConfigRepository configRepository,
            ProjectRepository projectRepository,
            AppUserRepository userRepository,
            ProjectPermissionService permissionService,
            ExperimentConfigValidator validator,
            Clock clock
    ) {
        this.configRepository = configRepository;
        this.projectRepository = projectRepository;
        this.userRepository = userRepository;
        this.permissionService = permissionService;
        this.validator = validator;
        this.clock = clock;
    }

    @Transactional
    public ExperimentConfig createVersion(long projectId, long currentUserId, String name, JsonNode spec) {
        permissionService.requireContribute(projectId, currentUserId);
        String normalizedName = normalizeName(name);
        JsonNode validatedSpec = validator.validate(spec);

        // Lock the stable project row so two concurrent requests for the same name
        // cannot both choose the same next version when no config row exists yet.
        Project project = projectRepository.findByIdForUpdate(projectId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId));
        int nextVersion = Math.addExact(configRepository.findLatestVersion(projectId, normalizedName), 1);
        AppUser creator = userRepository.getReferenceById(currentUserId);
        return configRepository.save(new ExperimentConfig(
                project,
                normalizedName,
                nextVersion,
                validatedSpec,
                creator,
                clock.instant()
        ));
    }

    @Transactional(readOnly = true)
    public List<ExperimentConfig> list(long projectId, long currentUserId) {
        permissionService.requireView(projectId, currentUserId);
        return configRepository.findAllByProject_IdOrderByNameAscVersionDesc(projectId);
    }

    @Transactional(readOnly = true)
    public ExperimentConfig get(long projectId, long configId, long currentUserId) {
        permissionService.requireView(projectId, currentUserId);
        return configRepository.findByIdAndProject_Id(configId, projectId)
                .orElseThrow(() -> new ExperimentConfigNotFoundException(projectId, configId));
    }

    private String normalizeName(String name) {
        if (name == null || name.trim().isEmpty() || name.trim().length() > 200) {
            throw new IllegalArgumentException("Config name must contain 1 to 200 characters");
        }
        return name.trim();
    }
}
