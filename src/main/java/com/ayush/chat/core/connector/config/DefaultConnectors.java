package com.ayush.chat.core.connector.config;

import com.ayush.chat.core.connector.ConnectorConfig;
import com.ayush.chat.core.connector.ConnectorKind;

import java.util.Map;
import java.util.Optional;

/**
 * The single home of built-in connector defaults: what a slot uses when application.yml says
 * nothing, and the safe local provider a slot may fall back to when its configured provider is
 * unreachable at startup.
 */
public final class DefaultConnectors {

    public static final String DEFAULT_STORE_URL = "jdbc:h2:file:./data/chat-store";
    public static final String DEFAULT_MEDIA_DIRECTORY = "./data/media";

    private DefaultConnectors() {
    }

    public static ConnectorConfig builtIn(ConnectorKind kind) {
        return switch (kind) {
            case MESSAGE_STORE -> new ConnectorConfig(kind, "h2", Map.of("url", DEFAULT_STORE_URL));
            case CACHE, EVENT_BUS -> new ConnectorConfig(kind, "memory", Map.of());
            case BLOB_STORE -> new ConnectorConfig(kind, "local", Map.of("directory", DEFAULT_MEDIA_DIRECTORY));
            case PUSH -> new ConnectorConfig(kind, "none", Map.of());
        };
    }

    /**
     * Fallback when the configured provider cannot start. The message store never falls back:
     * writing chats into a different database than configured is worse than failing.
     */
    public static Optional<ConnectorConfig> safeFallback(ConnectorKind kind) {
        return kind == ConnectorKind.MESSAGE_STORE ? Optional.empty() : Optional.of(builtIn(kind));
    }
}
