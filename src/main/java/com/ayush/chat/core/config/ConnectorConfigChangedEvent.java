package com.ayush.chat.core.config;

import com.ayush.chat.core.connector.ConnectorKind;

/**
 * Published (locally, and on other nodes via polling) when a connector config row changes.
 * {@code scopeKey} is {@code platform} or {@code tenant:<slug>}.
 */
public record ConnectorConfigChangedEvent(String scopeKey, ConnectorKind kind) {

    public static final String PLATFORM = "platform";

    public static String tenantScope(String slug) {
        return "tenant:" + slug;
    }

    public boolean isPlatform() {
        return PLATFORM.equals(scopeKey);
    }

    public String tenantSlug() {
        return scopeKey.startsWith("tenant:") ? scopeKey.substring("tenant:".length()) : null;
    }
}
