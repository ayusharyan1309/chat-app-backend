package com.ayush.chat.core.exception;

/** A plan limit is reached: users, messages per day, connections, storage or group size (429). */
public class QuotaExceededException extends DomainException {

    public QuotaExceededException(String message) {
        super(ErrorCode.QUOTA_EXCEEDED, message);
    }
}
