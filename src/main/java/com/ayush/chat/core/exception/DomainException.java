package com.ayush.chat.core.exception;

/**
 * Base of every expected failure of a business rule or request, independent of any transport: REST
 * maps it to an HTTP status ({@link ErrorHttpMapping}), STOMP to an {@code error} event. Throw a
 * specific subclass (or use the factories below); catch the subclass you can handle.
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

    public static BadRequestException badRequest(String message) {
        return new BadRequestException(message);
    }

    public static NotFoundException notFound(String what) {
        return new NotFoundException(what + " not found");
    }

    public static ForbiddenException forbidden(String message) {
        return new ForbiddenException(message);
    }

    public static UnauthorizedException unauthorized(String message) {
        return new UnauthorizedException(message);
    }

    public static ConflictException conflict(String message) {
        return new ConflictException(message);
    }

    public static FeatureDisabledException featureDisabled(String featureId) {
        return new FeatureDisabledException(featureId);
    }

    public static QuotaExceededException quotaExceeded(String message) {
        return new QuotaExceededException(message);
    }

    public static RateLimitedException rateLimited(String message) {
        return new RateLimitedException(message);
    }

    public static DomainException unavailable(String message) {
        return new DomainException(ErrorCode.UNAVAILABLE, message);
    }
}
