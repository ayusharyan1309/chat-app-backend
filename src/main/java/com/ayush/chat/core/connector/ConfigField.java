package com.ayush.chat.core.connector;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * One configurable setting of a connector. The dashboard renders forms from these, so a new
 * provider only needs to declare its fields — no UI work.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConfigField(
        String key,
        String label,
        FieldType type,
        boolean required,
        Object defaultValue,
        String description,
        String placeholder,
        List<String> options) {

    public enum FieldType {
        STRING, SECRET, INTEGER, BOOLEAN, SELECT, TEXTAREA, URL
    }

    public boolean isSecret() {
        return type == FieldType.SECRET;
    }

    public static Builder of(String key, String label) {
        return new Builder(key, label);
    }

    public static final class Builder {
        private final String key;
        private final String label;
        private FieldType type = FieldType.STRING;
        private boolean required;
        private Object defaultValue;
        private String description;
        private String placeholder;
        private List<String> options;

        private Builder(String key, String label) {
            this.key = key;
            this.label = label;
        }

        public Builder type(FieldType type) { this.type = type; return this; }
        public Builder secret() { this.type = FieldType.SECRET; return this; }
        public Builder integer(int defaultValue) { this.type = FieldType.INTEGER; this.defaultValue = defaultValue; return this; }
        public Builder bool(boolean defaultValue) { this.type = FieldType.BOOLEAN; this.defaultValue = defaultValue; return this; }
        public Builder select(String defaultValue, String... options) {
            this.type = FieldType.SELECT;
            this.defaultValue = defaultValue;
            this.options = List.of(options);
            return this;
        }
        public Builder textarea() { this.type = FieldType.TEXTAREA; return this; }
        public Builder url() { this.type = FieldType.URL; return this; }
        public Builder required() { this.required = true; return this; }
        public Builder defaultValue(Object value) { this.defaultValue = value; return this; }
        public Builder description(String description) { this.description = description; return this; }
        public Builder placeholder(String placeholder) { this.placeholder = placeholder; return this; }

        public ConfigField build() {
            return new ConfigField(key, label, type, required, defaultValue, description, placeholder, options);
        }
    }
}
