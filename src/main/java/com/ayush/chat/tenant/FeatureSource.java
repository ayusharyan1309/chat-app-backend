package com.ayush.chat.tenant;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/** Why a feature is on or off for a tenant. */
public enum FeatureSource {
    CORE, PLAN, OVERRIDE, PLANNED;

    @JsonValue
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
