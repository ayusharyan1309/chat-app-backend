package com.ayush.chat.core.connector.config;

import java.util.Objects;

/**
 * Where a connector configuration applies: the whole platform, or one tenant (override).
 * Persisted as {@code platform} / {@code tenant:<slug>}.
 */
public record ConfigScope(String tenantSlug) {

    public static final ConfigScope PLATFORM = new ConfigScope(null);

    /** Persisted key prefix of every tenant scope. */
    public static final String TENANT_PREFIX = "tenant:";
    private static final String PLATFORM_KEY = "platform";

    public static ConfigScope tenant(String slug) {
        return new ConfigScope(Objects.requireNonNull(slug, "slug"));
    }

    public static ConfigScope parse(String key) {
        if (PLATFORM_KEY.equals(key)) {
            return PLATFORM;
        }
        if (key != null && key.startsWith(TENANT_PREFIX)) {
            return tenant(key.substring(TENANT_PREFIX.length()));
        }
        throw new IllegalArgumentException("Unknown config scope: " + key);
    }

    public boolean isPlatform() {
        return tenantSlug == null;
    }

    public String key() {
        return isPlatform() ? PLATFORM_KEY : TENANT_PREFIX + tenantSlug;
    }

    @Override
    public String toString() {
        return key();
    }
}
