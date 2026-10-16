package com.ayush.chat.core.connector;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Typed, read-only view over a connector's settings map with descriptor defaults applied.
 */
public final class ConnectorSettings {

    private final Map<String, Object> values;

    public ConnectorSettings(Map<String, Object> values) {
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values == null ? Map.of() : values));
    }

    public static ConnectorSettings withDefaults(ConnectorDescriptor descriptor, Map<String, Object> raw) {
        Map<String, Object> merged = new LinkedHashMap<>();
        for (ConfigField field : descriptor.fields()) {
            if (field.defaultValue() != null) {
                merged.put(field.key(), field.defaultValue());
            }
        }
        if (raw != null) {
            raw.forEach((k, v) -> {
                if (v != null && !(v instanceof String s && s.isBlank())) {
                    merged.put(k, v);
                }
            });
        }
        return new ConnectorSettings(merged);
    }

    public Map<String, Object> asMap() {
        return values;
    }

    public boolean has(String key) {
        Object v = values.get(key);
        return v != null && !(v instanceof String s && s.isBlank());
    }

    public String string(String key) {
        Object v = values.get(key);
        return v == null ? null : String.valueOf(v);
    }

    public String string(String key, String fallback) {
        return has(key) ? string(key) : fallback;
    }

    public String required(String key) {
        if (!has(key)) {
            throw new IllegalArgumentException("Missing required setting: " + key);
        }
        return string(key);
    }

    public int integer(String key, int fallback) {
        Object v = values.get(key);
        if (v == null) return fallback;
        if (v instanceof Number n) return n.intValue();
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? fallback : Integer.parseInt(s);
    }

    public long longValue(String key, long fallback) {
        Object v = values.get(key);
        if (v == null) return fallback;
        if (v instanceof Number n) return n.longValue();
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? fallback : Long.parseLong(s);
    }

    public boolean bool(String key, boolean fallback) {
        Object v = values.get(key);
        if (v == null) return fallback;
        if (v instanceof Boolean b) return b;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? fallback : Boolean.parseBoolean(s);
    }
}
