package com.labflow.backend.job;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobSubmissionService submissionService;
    private final JobQueryService queryService;
    private final JobLogStreamService logStreamService;

    public JobController(
            JobSubmissionService submissionService,
            JobQueryService queryService,
            JobLogStreamService logStreamService
    ) {
        this.submissionService = submissionService;
        this.queryService = queryService;
        this.logStreamService = logStreamService;
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

    @GetMapping("/{jobId}")
    public JobDetailsResponse get(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long jobId
    ) {
        return queryService.get(jobId, Long.parseLong(jwt.getSubject()));
    }

    @GetMapping(path = "/{jobId}/events", produces = "text/event-stream")
    public SseEmitter events(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long jobId,
            @RequestHeader(name = "Last-Event-ID", defaultValue = "0") long lastEventId
    ) {
        return logStreamService.subscribe(jobId, Long.parseLong(jwt.getSubject()), lastEventId);
    }
}
