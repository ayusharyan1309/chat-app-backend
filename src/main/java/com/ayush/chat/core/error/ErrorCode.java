package com.ayush.chat.core.error;

/**
 * Stable, machine-readable error codes. They are part of the public API contract (the
 * {@code error} field of every error body); the HTTP mapping lives in {@link ErrorHttpMapping}.
 */
public enum ErrorCode {
    BAD_REQUEST("bad_request"),
    VALIDATION_FAILED("validation_failed"),
    UNAUTHORIZED("unauthorized"),
    FORBIDDEN("forbidden"),
    NOT_FOUND("not_found"),
    CONFLICT("conflict"),
    FEATURE_DISABLED("feature_disabled"),
    QUOTA_EXCEEDED("quota_exceeded"),
    RATE_LIMITED("rate_limited"),
    PAYLOAD_TOO_LARGE("payload_too_large"),
    METHOD_NOT_ALLOWED("method_not_allowed"),
    UNAVAILABLE("unavailable"),
    INTERNAL("internal_error");

    private final String code;

    ErrorCode(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
