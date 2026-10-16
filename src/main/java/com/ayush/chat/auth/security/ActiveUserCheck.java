package com.ayush.chat.auth.security;

/**
 * Port: is this end user allowed to act? Implemented by the chat user directory; owned here so
 * authentication never depends on the messaging domain.
 */
public interface ActiveUserCheck {

    /** Throws a {@code DomainException} (forbidden) unless the user exists and is active. */
    void requireActiveUser(String tenant, String userId);
}
