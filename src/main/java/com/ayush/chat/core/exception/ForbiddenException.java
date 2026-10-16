package com.ayush.chat.core.exception;

/** Authenticated, but not allowed: wrong role, suspended tenant, deactivated or blocked user (403). */
public class ForbiddenException extends DomainException {

    public ForbiddenException(String message) {
        super(ErrorCode.FORBIDDEN, message);
    }
}
