package com.ayush.chat.core.exception;

/** The change conflicts with existing state: duplicate slug or email, last owner, plan in use (409). */
public class ConflictException extends DomainException {

    public ConflictException(String message) {
        super(ErrorCode.CONFLICT, message);
    }
}
