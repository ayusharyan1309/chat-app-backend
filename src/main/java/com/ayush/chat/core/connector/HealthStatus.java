package com.ayush.chat.core.connector;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record HealthStatus(boolean healthy, String message, Long latencyMs, Map<String, Object> details) {

    public static HealthStatus up(String message) {
        return new HealthStatus(true, message, null, null);
    }

    public static HealthStatus up(String message, Map<String, Object> details) {
        return new HealthStatus(true, message, null, details);
    }

    public static HealthStatus down(String message) {
        return new HealthStatus(false, message, null, null);
    }

    public HealthStatus withLatency(long latencyMs) {
        return new HealthStatus(healthy, message, latencyMs, details);
    }
}
