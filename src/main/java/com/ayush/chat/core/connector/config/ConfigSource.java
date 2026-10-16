package com.ayush.chat.core.connector.config;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Where the active configuration of a slot comes from.
 */
public enum ConfigSource {
    /** application.yml / environment. */
    BOOTSTRAP,
    /** Saved from the dashboard into the control plane. */
    DATABASE;

    @JsonValue
    public String json() {
        return name().toLowerCase();
    }
}
