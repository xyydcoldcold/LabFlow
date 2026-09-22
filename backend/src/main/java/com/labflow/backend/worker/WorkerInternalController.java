package com.labflow.backend.worker;

import jakarta.validation.Valid;

import com.labflow.backend.job.JobLogChunkResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal")
public class WorkerInternalController {

    private final WorkerExecutionService executionService;

    public WorkerInternalController(WorkerExecutionService executionService) {
        this.executionService = executionService;
    }

    @PostMapping("/workers/register")
    public WorkerRegistrationResponse register(@Valid @RequestBody RegisterWorkerRequest request) {
        return executionService.register(request);
    }

    @PostMapping("/jobs/{jobId}/claim")
    public ClaimJobResponse claim(
            @PathVariable long jobId,
            @Valid @RequestBody ClaimJobRequest request
    ) {
        return executionService.claim(jobId, request.workerId());
    }

    @PostMapping("/attempts/{attemptId}/logs")
    @ResponseStatus(HttpStatus.CREATED)
    public JobLogChunkResponse appendLog(
            @PathVariable long attemptId,
            @RequestHeader("X-Attempt-Token") String token,
            @Valid @RequestBody AppendLogRequest request
    ) {
        return executionService.appendLog(attemptId, token, request);
    }

    @PostMapping("/attempts/{attemptId}/succeed")
    public AttemptCompletionResponse succeed(
            @PathVariable long attemptId,
            @RequestHeader("X-Attempt-Token") String token,
            @Valid @RequestBody CompleteAttemptRequest request
    ) {
        return executionService.succeed(attemptId, token, request);
    }

    @PostMapping("/attempts/{attemptId}/fail")
    public AttemptCompletionResponse fail(
            @PathVariable long attemptId,
            @RequestHeader("X-Attempt-Token") String token,
            @Valid @RequestBody FailAttemptRequest request
    ) {
        return executionService.fail(attemptId, token, request);
    }
}
