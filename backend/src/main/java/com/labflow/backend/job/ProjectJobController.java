package com.labflow.backend.job;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}/jobs")
public class ProjectJobController {

    private final JobQueryService queryService;

    public ProjectJobController(JobQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public List<JobSummaryResponse> list(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long projectId
    ) {
        return queryService.list(projectId, Long.parseLong(jwt.getSubject()));
    }
}
