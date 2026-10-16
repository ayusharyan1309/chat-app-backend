package com.ayush.chat.core.error;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Domain exception carrying an HTTP status and a stable machine-readable code.
 */
@Getter
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "bad_request", message);
    }

    public static ApiException notFound(String what) {
        return new ApiException(HttpStatus.NOT_FOUND, "not_found", what + " not found");
    }

    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, "forbidden", message);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, "conflict", message);
    }

    public static ApiException featureDisabled(String featureId) {
        return new ApiException(HttpStatus.FORBIDDEN, "feature_disabled",
                "Feature '" + featureId + "' is not enabled for this tenant");
    }

    public static ApiException quotaExceeded(String message) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "quota_exceeded", message);
    }

    public static ApiException rateLimited(String message) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", message);
    }

    public static ApiException unavailable(String message) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "unavailable", message);
    }
}
