package com.ayush.chat.core.connector;

import java.util.List;

/**
 * Self-description of a connector provider: identity, compatibility and its config schema.
 *
 * @param maturity    see {@link Maturity}
 * @param compatibleWith other products speaking the same protocol (e.g. Supabase for PostgreSQL)
 */
public record ConnectorDescriptor(
        ConnectorKind kind,
        String id,
        String displayName,
        String description,
        String category,
        Maturity maturity,
        List<String> compatibleWith,
        List<ConfigField> fields) {

    public ConfigField field(String key) {
        return fields.stream().filter(f -> f.key().equals(key)).findFirst().orElse(null);
    }
}
