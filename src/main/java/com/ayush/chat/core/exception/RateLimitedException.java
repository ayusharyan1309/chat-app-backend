package com.ayush.chat.core.exception;

/** Too many requests in a short window, e.g. sends per second or admin login attempts (429). */
public class RateLimitedException extends DomainException {

    public RateLimitedException(String message) {
        super(ErrorCode.RATE_LIMITED, message);
    }
}
