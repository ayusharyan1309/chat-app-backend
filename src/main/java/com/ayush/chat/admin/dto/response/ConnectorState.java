package com.ayush.chat.admin.dto.response;

import com.ayush.chat.core.connector.HealthStatus;
import com.ayush.chat.core.connector.config.ConfigSource;

import java.time.Instant;
import java.util.Map;

public record ConnectorState(String kind, String provider, Map<String, Object> settings, ConfigSource source, Long version,
                             Instant updatedAt, String updatedBy, HealthStatus health, String lastError) {
}
