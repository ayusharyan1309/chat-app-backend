package com.ayush.chat.admin.dto.response;

import com.ayush.chat.core.connector.config.ConfigSource;

import java.util.Map;

public record ComponentView(String kind, String displayName, String provider, String providerName, ConfigSource source,
                            boolean healthy, String message, Long latencyMs, Map<String, Object> details,
                            String lastError) {
}
