package com.labflow.backend.common.api;

import java.time.Instant;

import jakarta.servlet.http.HttpServletRequest;

import com.labflow.backend.artifact.ArtifactStorageException;
import com.labflow.backend.auth.EmailAlreadyRegisteredException;
import com.labflow.backend.auth.InvalidCredentialsException;
import com.labflow.backend.experimentconfig.ExperimentConfigNotFoundException;
import com.labflow.backend.experimentconfig.InvalidExperimentConfigException;
import com.labflow.backend.job.IdempotencyKeyReusedException;
import com.labflow.backend.job.JobResourceNotFoundException;
import com.labflow.backend.job.JobNotFoundException;
import com.labflow.backend.molecularinput.InvalidMolecularInputException;
import com.labflow.backend.molecularinput.MolecularInputNotFoundException;
import com.labflow.backend.molecularinput.MolecularInputReadException;
import com.labflow.backend.molecularinput.MolecularInputTooLargeException;
import com.labflow.backend.project.ProjectAccessDeniedException;
import com.labflow.backend.project.ProjectMemberAlreadyExistsException;
import com.labflow.backend.project.ProjectMemberNotFoundException;
import com.labflow.backend.project.ProjectMemberUserNotFoundException;
import com.labflow.backend.project.ProjectNotFoundException;
import com.labflow.backend.worker.AttemptNotFoundException;
import com.labflow.backend.worker.JobNotClaimableException;
import com.labflow.backend.worker.StaleAttemptException;
import com.labflow.backend.worker.WorkerNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(EmailAlreadyRegisteredException.class)
    ResponseEntity<ApiErrorResponse> handleEmailAlreadyRegistered(
            EmailAlreadyRegisteredException exception,
            HttpServletRequest request
    ) {
        return error(HttpStatus.CONFLICT, "EMAIL_ALREADY_REGISTERED", exception.getMessage(), request);
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    ResponseEntity<ApiErrorResponse> handleInvalidCredentials(
            InvalidCredentialsException exception,
            HttpServletRequest request
    ) {
        return error(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", exception.getMessage(), request);
    }

    @ExceptionHandler(ProjectNotFoundException.class)
    ResponseEntity<ApiErrorResponse> handleProjectNotFound(
            ProjectNotFoundException exception,
            HttpServletRequest request
    ) {
        return error(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", exception.getMessage(), request);
    }

    @ExceptionHandler(ProjectMemberUserNotFoundException.class)
    ResponseEntity<ApiErrorResponse> handleProjectMemberUserNotFound(
            ProjectMemberUserNotFoundException exception,
            HttpServletRequest request
    ) {
        return error(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", exception.getMessage(), request);
    }

    @ExceptionHandler(ProjectMemberNotFoundException.class)
    ResponseEntity<ApiErrorResponse> handleProjectMemberNotFound(
            ProjectMemberNotFoundException exception,
            HttpServletRequest request
    ) {
        return error(HttpStatus.NOT_FOUND, "PROJECT_MEMBER_NOT_FOUND", exception.getMessage(), request);
    }

    @ExceptionHandler(ProjectMemberAlreadyExistsException.class)
    ResponseEntity<ApiErrorResponse> handleProjectMemberAlreadyExists(
            ProjectMemberAlreadyExistsException exception,
            HttpServletRequest request
    ) {
        return error(HttpStatus.CONFLICT, "PROJECT_MEMBER_ALREADY_EXISTS", exception.getMessage(), request);
    }

    @ExceptionHandler(ProjectAccessDeniedException.class)
    ResponseEntity<ApiErrorResponse> handleProjectAccessDenied(
            ProjectAccessDeniedException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.FORBIDDEN,
                "PROJECT_ACCESS_DENIED",
                "You do not have permission to access this project",
                request
        );
    }

    @ExceptionHandler(MolecularInputNotFoundException.class)
    ResponseEntity<ApiErrorResponse> handleMolecularInputNotFound(
            MolecularInputNotFoundException exception,
            HttpServletRequest request
    ) {
        return error(HttpStatus.NOT_FOUND, "MOLECULAR_INPUT_NOT_FOUND", exception.getMessage(), request);
    }

    @ExceptionHandler(ExperimentConfigNotFoundException.class)
    ResponseEntity<ApiErrorResponse> handleExperimentConfigNotFound(
            ExperimentConfigNotFoundException exception,
            HttpServletRequest request
    ) {
        return error(HttpStatus.NOT_FOUND, "EXPERIMENT_CONFIG_NOT_FOUND", exception.getMessage(), request);
    }

    @ExceptionHandler(InvalidExperimentConfigException.class)
    ResponseEntity<ApiErrorResponse> handleInvalidExperimentConfig(
            InvalidExperimentConfigException exception,
            HttpServletRequest request
    ) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_EXPERIMENT_CONFIG", exception.getMessage(), request);
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    ResponseEntity<ApiErrorResponse> handleIdempotencyKeyReused(
            IdempotencyKeyReusedException exception,
            HttpServletRequest request
    ) {
        return error(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", exception.getMessage(), request);
    }

    @ExceptionHandler(JobResourceNotFoundException.class)
    ResponseEntity<ApiErrorResponse> handleJobResourceNotFound(
            JobResourceNotFoundException exception,
            HttpServletRequest request
    ) {
        return error(HttpStatus.NOT_FOUND, "JOB_RESOURCE_NOT_FOUND", exception.getMessage(), request);
    }

    @ExceptionHandler({JobNotFoundException.class, AttemptNotFoundException.class, WorkerNotFoundException.class})
    ResponseEntity<ApiErrorResponse> handleWorkerResourceNotFound(
            RuntimeException exception,
            HttpServletRequest request
    ) {
        return error(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", exception.getMessage(), request);
    }

    @ExceptionHandler(JobNotClaimableException.class)
    ResponseEntity<ApiErrorResponse> handleJobNotClaimable(
            JobNotClaimableException exception,
            HttpServletRequest request
    ) {
        return error(HttpStatus.CONFLICT, "JOB_NOT_CLAIMABLE", exception.getMessage(), request);
    }

    @ExceptionHandler(StaleAttemptException.class)
    ResponseEntity<ApiErrorResponse> handleStaleAttempt(
            StaleAttemptException exception,
            HttpServletRequest request
    ) {
        return error(HttpStatus.CONFLICT, "STALE_ATTEMPT", exception.getMessage(), request);
    }

    @ExceptionHandler(InvalidMolecularInputException.class)
    ResponseEntity<ApiErrorResponse> handleInvalidMolecularInput(
            InvalidMolecularInputException exception,
            HttpServletRequest request
    ) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_MOLECULAR_INPUT", exception.getMessage(), request);
    }

    @ExceptionHandler({MolecularInputTooLargeException.class, MaxUploadSizeExceededException.class})
    ResponseEntity<ApiErrorResponse> handleMolecularInputTooLarge(
            Exception exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.CONTENT_TOO_LARGE,
                "MOLECULAR_INPUT_TOO_LARGE",
                "Molecular input exceeds the maximum upload size",
                request
        );
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    ResponseEntity<ApiErrorResponse> handleMissingRequestPart(
            MissingServletRequestPartException exception,
            HttpServletRequest request
    ) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Required upload part is missing", request);
    }

    @ExceptionHandler({MolecularInputReadException.class, ArtifactStorageException.class})
    ResponseEntity<ApiErrorResponse> handleArtifactFailure(
            RuntimeException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "ARTIFACT_OPERATION_FAILED",
                "The artifact operation could not be completed",
                request
        );
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiErrorResponse> handleAccessDenied(
            AccessDeniedException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.FORBIDDEN,
                "FORBIDDEN",
                "You do not have permission to access this resource",
                request
        );
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            MissingRequestHeaderException.class,
            HttpMessageNotReadableException.class,
            IllegalArgumentException.class
    })
    ResponseEntity<ApiErrorResponse> handleBadRequest(Exception exception, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", request);
    }

    private ResponseEntity<ApiErrorResponse> error(
            HttpStatus status,
            String code,
            String message,
            HttpServletRequest request
    ) {
        ApiErrorResponse response = new ApiErrorResponse(
                Instant.now(),
                status.value(),
                code,
                message,
                request.getRequestURI(),
                request.getHeader("X-Request-ID")
        );
        return ResponseEntity.status(status).body(response);
    }
}
