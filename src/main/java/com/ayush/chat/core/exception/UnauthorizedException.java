package com.ayush.chat.core.exception;

/** Missing, invalid or expired credentials (401). */
public class UnauthorizedException extends DomainException {

    public UnauthorizedException(String message) {
        super(ErrorCode.UNAUTHORIZED, message);
    }
}
