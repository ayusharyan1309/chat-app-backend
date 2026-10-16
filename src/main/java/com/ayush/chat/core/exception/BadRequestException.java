package com.ayush.chat.core.exception;

/** The request breaks a rule: malformed input, an invalid value or an unsupported operation (400). */
public class BadRequestException extends DomainException {

    public BadRequestException(String message) {
        super(ErrorCode.BAD_REQUEST, message);
    }
}
