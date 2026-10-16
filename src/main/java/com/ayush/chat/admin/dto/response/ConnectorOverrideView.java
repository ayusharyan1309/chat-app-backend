package com.ayush.chat.admin.dto.response;

import com.ayush.chat.core.connector.HealthStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/** A tenant's dedicated connector, or {@code inherited} when it uses the platform default. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConnectorOverrideView(boolean inherited, String provider, Map<String, Object> settings, HealthStatus health) {
    public static ConnectorOverrideView inheritedView() {
        return new ConnectorOverrideView(true, null, null, null);
    }
}
