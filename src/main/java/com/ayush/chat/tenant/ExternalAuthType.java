package com.ayush.chat.tenant;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.ayush.chat.core.error.DomainException;

import java.util.Arrays;
import java.util.Locale;

/** How a tenant's end users can sign in besides platform-issued tokens. */
public enum ExternalAuthType {
    NONE, FIREBASE, JWT;

    @JsonValue
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static ExternalAuthType fromId(String id) {
        if (id == null || id.isBlank()) {
            return NONE;
        }
        return Arrays.stream(values()).filter(t -> t.id().equalsIgnoreCase(id.trim())).findFirst()
                .orElseThrow(() -> DomainException.badRequest("external.type must be none, firebase or jwt"));
    }
}
