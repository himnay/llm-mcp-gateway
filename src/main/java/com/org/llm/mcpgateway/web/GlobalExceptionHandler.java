package com.org.llm.mcpgateway.web;

import com.org.llm.mcpgateway.exception.BackendUnavailableException;
import com.org.llm.mcpgateway.exception.GatewayException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleBodyValidation(MethodArgumentNotValidException ex,
                                                         HttpServletRequest req) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .toList();
        log.warn("Validation failed | {}", details);
        return build(HttpStatus.BAD_REQUEST, "Validation failed", "One or more fields are invalid",
                req.getRequestURI(), details);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException ex,
                                                              HttpServletRequest req) {
        List<String> details = ex.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .toList();
        log.warn("Validation failed | {}", details);
        return build(HttpStatus.BAD_REQUEST, "Validation failed", "Constraint violation",
                req.getRequestURI(), details);
    }

    @ExceptionHandler(BackendUnavailableException.class)
    public ResponseEntity<ApiError> handleBackendUnavailable(BackendUnavailableException ex,
                                                             HttpServletRequest req) {
        log.warn("Backend unavailable | {}", ex.getMessage());
        return build(HttpStatus.BAD_GATEWAY, "Bad Gateway", ex.getMessage(), req.getRequestURI(), null);
    }

    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<ApiError> handleGatewayException(GatewayException ex,
                                                           HttpServletRequest req) {
        log.error("Internal gateway error", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error",
                "An internal error occurred. Please try again.", req.getRequestURI(), null);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleBadRequest(IllegalArgumentException ex,
                                                     HttpServletRequest req) {
        log.warn("Bad request | {}", ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, "Bad Request", ex.getMessage(), req.getRequestURI(), null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGeneric(Exception ex, HttpServletRequest req) {
        log.error("Unhandled exception", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error",
                "An internal error occurred. Please try again.", req.getRequestURI(), null);
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String error, String message,
                                           String path, List<String> details) {
        return ResponseEntity.status(status)
                .body(ApiError.of(status.value(), error, message, path, details));
    }
}
