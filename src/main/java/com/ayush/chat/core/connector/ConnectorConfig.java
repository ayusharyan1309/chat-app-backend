package com.ayush.chat.core.connector;

import java.util.Map;

/**
 * Which provider to use for a slot, and its settings (plain-text; secrets already decrypted).
 */
public record ConnectorConfig(ConnectorKind kind, String provider, Map<String, Object> settings) {

    public ConnectorConfig {
        settings = settings == null ? Map.of() : Map.copyOf(settings.entrySet().stream()
                .filter(e -> e.getValue() != null)
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
    }
}
