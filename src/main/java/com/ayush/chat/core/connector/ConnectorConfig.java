package com.ayush.chat.core.connector;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Which provider to use for a slot, and its settings (plain-text; secrets already decrypted).
 */
public record ConnectorConfig(ConnectorKind kind, String provider, Map<String, Object> settings) {

    public ConnectorConfig {
        settings = settings == null ? Map.of() : Map.copyOf(settings.entrySet().stream()
                .filter(e -> e.getValue() != null)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
    }
}
