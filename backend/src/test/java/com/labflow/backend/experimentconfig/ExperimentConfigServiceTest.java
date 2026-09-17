package com.labflow.backend.experimentconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import com.labflow.backend.auth.AppUser;
import com.labflow.backend.auth.AppUserRepository;
import com.labflow.backend.project.Project;
import com.labflow.backend.project.ProjectPermissionService;
import com.labflow.backend.project.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

@ExtendWith(MockitoExtension.class)
class ExperimentConfigServiceTest {

    private static final long PROJECT_ID = 10L;
    private static final long USER_ID = 20L;
    private static final Instant NOW = Instant.parse("2026-09-16T12:00:00Z");

    @Mock private ExperimentConfigRepository configRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private AppUserRepository userRepository;
    @Mock private ProjectPermissionService permissionService;

    private ExperimentConfigService service;

    @BeforeEach
    void setUp() {
        service = new ExperimentConfigService(
                configRepository,
                projectRepository,
                userRepository,
                permissionService,
                new ExperimentConfigValidator(),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void sameNameCreatesTheNextImmutableVersion() {
        Project project = mock(Project.class);
        AppUser user = mock(AppUser.class);
        when(project.getId()).thenReturn(PROJECT_ID);
        when(user.getId()).thenReturn(USER_ID);
        when(projectRepository.findByIdForUpdate(PROJECT_ID)).thenReturn(java.util.Optional.of(project));
        when(userRepository.getReferenceById(USER_ID)).thenReturn(user);
        when(configRepository.findLatestVersion(PROJECT_ID, "Baseline")).thenReturn(1);
        when(configRepository.save(org.mockito.ArgumentMatchers.any(ExperimentConfig.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ObjectNode submitted = validSpec();
        ExperimentConfig created = service.createVersion(PROJECT_ID, USER_ID, " Baseline ", submitted);
        submitted.put("basis", "changed-after-save");

        assertThat(created.getName()).isEqualTo("Baseline");
        assertThat(created.getVersion()).isEqualTo(2);
        assertThat(created.getSpec().get("basis").stringValue()).isEqualTo("sto-3g");
        assertThat(created.getCreatedAt()).isEqualTo(NOW);
        verify(permissionService).requireContribute(PROJECT_ID, USER_ID);
        verify(projectRepository).findByIdForUpdate(PROJECT_ID);
    }

    @Test
    void listAndGetRequireProjectVisibilityAndUseProjectScopedQueries() {
        when(configRepository.findByIdAndProject_Id(30L, PROJECT_ID))
                .thenReturn(java.util.Optional.of(mock(ExperimentConfig.class)));

        service.list(PROJECT_ID, USER_ID);
        service.get(PROJECT_ID, 30L, USER_ID);

        verify(permissionService, org.mockito.Mockito.times(2)).requireView(PROJECT_ID, USER_ID);
        verify(configRepository).findAllByProject_IdOrderByNameAscVersionDesc(PROJECT_ID);
        verify(configRepository).findByIdAndProject_Id(30L, PROJECT_ID);
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
