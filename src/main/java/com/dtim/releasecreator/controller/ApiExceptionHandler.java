package com.dtim.releasecreator.controller;

import com.dtim.releasecreator.dto.ApiError;
import com.dtim.releasecreator.exception.IntegrationException;
import com.dtim.releasecreator.exception.InvalidReleaseNumberException;
import com.dtim.releasecreator.exception.ReleaseBranchConflictException;
import com.dtim.releasecreator.exception.ReleaseInProgressException;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler({InvalidReleaseNumberException.class, MethodArgumentNotValidException.class})
    public ResponseEntity<ApiError> badRequest(Exception exception) {
        List<String> details = exception instanceof MethodArgumentNotValidException validationException
                ? validationException.getBindingResult().getFieldErrors().stream()
                        .map(error -> error.getField() + ": " + error.getDefaultMessage())
                        .toList()
                : List.of();
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage(), details);
    }

    @ExceptionHandler(ReleaseBranchConflictException.class)
    public ResponseEntity<ApiError> releaseConflict(ReleaseBranchConflictException exception) {
        return error(HttpStatus.CONFLICT, "RELEASE_BRANCH_ALREADY_EXISTS", exception.getMessage(), exception.getRepositories());
    }

    @ExceptionHandler(ReleaseInProgressException.class)
    public ResponseEntity<ApiError> releaseInProgress(ReleaseInProgressException exception) {
        return error(HttpStatus.CONFLICT, "RELEASE_IN_PROGRESS", exception.getMessage(), List.of());
    }

    @ExceptionHandler(IntegrationException.class)
    public ResponseEntity<ApiError> integrationFailure(IntegrationException exception) {
        log.error("Critical integration request failed", exception);
        return error(HttpStatus.BAD_GATEWAY, "INTEGRATION_FAILURE", exception.getMessage(), List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpectedFailure(Exception exception) {
        log.error("Unexpected request failure", exception);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected server error", List.of());
    }

    private ResponseEntity<ApiError> error(
            HttpStatus status,
            String code,
            String message,
            List<String> details) {
        return ResponseEntity.status(status).body(new ApiError(Instant.now(), status.value(), code, message, details));
    }
}
