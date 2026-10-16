package com.ayush.chat.core.exception;

/**
 * The resource does not exist — or the caller must not learn that it does, as with conversations
 * they are not a member of (404).
 */
public class NotFoundException extends DomainException {

    public NotFoundException(String message) {
        super(ErrorCode.NOT_FOUND, message);
    }
}
