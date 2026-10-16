package com.ayush.chat.core.error;

import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

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

    public static Map<String, Object> body(ErrorCode code, String message, String path) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status(code).value());
        body.put("error", code.code());
        body.put("message", message);
        body.put("path", path);
        body.put("timestamp", Instant.now().toString());
        return body;
    }
}
