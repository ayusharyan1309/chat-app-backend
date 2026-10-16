package com.ayush.chat.core.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Maps exceptions to the stable error envelope. Only {@link DomainException}s and Spring's own
 * request-binding failures produce 4xx; anything else is a bug, logged with its stack trace and
 * reported as an opaque 500 so internal details never reach clients.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<Map<String, Object>> handleDomain(DomainException e, HttpServletRequest request) {
        if (e instanceof InfrastructureUnavailableException) {
            log.warn("{} {} failed: {}", request.getMethod(), request.getRequestURI(), e.getMessage(), e.getCause());
        }
        return respond(e.getErrorCode(), e.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e, HttpServletRequest request) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return respond(ErrorCode.VALIDATION_FAILED, message, request);
    }

    @ExceptionHandler({ConstraintViolationException.class, HandlerMethodValidationException.class})
    public ResponseEntity<Map<String, Object>> handleConstraint(Exception e, HttpServletRequest request) {
        return respond(ErrorCode.VALIDATION_FAILED, e.getMessage(), request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException e, HttpServletRequest request) {
        return respond(ErrorCode.BAD_REQUEST, "Malformed request body", request);
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MissingRequestHeaderException.class,
            MissingServletRequestPartException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, Object>> handleBadInput(Exception e, HttpServletRequest request) {
        return respond(ErrorCode.BAD_REQUEST, e.getMessage(), request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleDenied(AccessDeniedException e, HttpServletRequest request) {
        return respond(ErrorCode.FORBIDDEN, "Access denied", request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleUpload(MaxUploadSizeExceededException e, HttpServletRequest request) {
        return respond(ErrorCode.PAYLOAD_TOO_LARGE, "Upload exceeds the size limit", request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethod(HttpRequestMethodNotSupportedException e, HttpServletRequest request) {
        return respond(ErrorCode.METHOD_NOT_ALLOWED, e.getMessage(), request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResource(NoResourceFoundException e, HttpServletRequest request) {
        return respond(ErrorCode.NOT_FOUND, "No endpoint " + request.getRequestURI(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e, HttpServletRequest request) {
        log.error("Unhandled error on {} {}", request.getMethod(), request.getRequestURI(), e);
        return respond(ErrorCode.INTERNAL, "Unexpected server error", request);
    }

    private static ResponseEntity<Map<String, Object>> respond(ErrorCode code, String message, HttpServletRequest request) {
        return ResponseEntity.status(ErrorHttpMapping.status(code))
                .body(ErrorHttpMapping.body(code, message, request.getRequestURI()));
    }
}
