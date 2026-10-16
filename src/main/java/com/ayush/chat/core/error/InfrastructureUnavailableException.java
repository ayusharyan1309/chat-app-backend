package com.ayush.chat.core.error;

/**
 * A connector's backend (database, cache, broker, object store) failed or is unreachable.
 * Rendered as 503 {@code unavailable}: the request is safe to retry, and the underlying cause is
 * logged rather than returned to clients.
 */
public class InfrastructureUnavailableException extends DomainException {

    public InfrastructureUnavailableException(String component, Throwable cause) {
        super(ErrorCode.UNAVAILABLE, component + " is temporarily unavailable", cause);
    }

    public InfrastructureUnavailableException(String component, String detail) {
        super(ErrorCode.UNAVAILABLE, component + " is temporarily unavailable: " + detail);
    }
}
