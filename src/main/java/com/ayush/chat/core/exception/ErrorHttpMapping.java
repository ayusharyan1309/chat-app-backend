package com.ayush.chat.core.exception;

import com.ayush.chat.core.dto.ErrorResponse;
import org.springframework.http.HttpStatus;

import java.time.Instant;

/**
 * The one place where domain error codes become HTTP statuses and error bodies.
 */
public final class ErrorHttpMapping {

    private ErrorHttpMapping() {
    }

    public static HttpStatus status(ErrorCode code) {
        return switch (code) {
            case BAD_REQUEST, VALIDATION_FAILED -> HttpStatus.BAD_REQUEST;
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN, FEATURE_DISABLED -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case QUOTA_EXCEEDED, RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case PAYLOAD_TOO_LARGE -> HttpStatus.PAYLOAD_TOO_LARGE;
            case METHOD_NOT_ALLOWED -> HttpStatus.METHOD_NOT_ALLOWED;
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case INTERNAL -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    public static ErrorResponse body(ErrorCode code, String message, String path) {
        return new ErrorResponse(status(code).value(), code.code(), message, path, Instant.now());
    }
}
