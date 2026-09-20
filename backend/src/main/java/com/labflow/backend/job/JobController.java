package com.labflow.backend.job;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobSubmissionService submissionService;

    public JobController(JobSubmissionService submissionService) {
        this.submissionService = submissionService;
    }

    @PostMapping
    public ResponseEntity<JobResponse> submit(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody JsonNode body
    ) {
        JobResponse job = submissionService.submit(
                Long.parseLong(jwt.getSubject()), idempotencyKey, CreateJobRequest.from(body)
        );
        URI location = URI.create("/api/jobs/" + job.id());
        return ResponseEntity.created(location).body(job);
    }
}
