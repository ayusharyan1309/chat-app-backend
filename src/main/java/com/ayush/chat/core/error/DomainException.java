package com.ayush.chat.core.error;

/**
 * An expected failure of a business rule or request, independent of any transport: REST maps it
 * to an HTTP status ({@link ErrorHttpMapping}), STOMP to an {@code error} event.
 */
public class DomainException extends RuntimeException {

    private final ErrorCode errorCode;

    public DomainException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public DomainException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    /** The public machine-readable code, e.g. {@code feature_disabled}. */
    public String getCode() {
        return errorCode.code();
    }

    public static DomainException badRequest(String message) {
        return new DomainException(ErrorCode.BAD_REQUEST, message);
    }

    public static DomainException notFound(String what) {
        return new DomainException(ErrorCode.NOT_FOUND, what + " not found");
    }

    public static DomainException forbidden(String message) {
        return new DomainException(ErrorCode.FORBIDDEN, message);
    }

    public static DomainException unauthorized(String message) {
        return new DomainException(ErrorCode.UNAUTHORIZED, message);
    }

    public static DomainException conflict(String message) {
        return new DomainException(ErrorCode.CONFLICT, message);
    }

    public static DomainException featureDisabled(String featureId) {
        return new DomainException(ErrorCode.FEATURE_DISABLED, "Feature '" + featureId + "' is not enabled for this tenant");
    }

    public static DomainException quotaExceeded(String message) {
        return new DomainException(ErrorCode.QUOTA_EXCEEDED, message);
    }

    public static DomainException rateLimited(String message) {
        return new DomainException(ErrorCode.RATE_LIMITED, message);
    }

    public static DomainException unavailable(String message) {
        return new DomainException(ErrorCode.UNAVAILABLE, message);
    }
}
