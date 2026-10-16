package com.ayush.chat.core.persistence;

import com.ayush.chat.core.util.Json;
import jakarta.persistence.AttributeConverter;

/**
 * Stores a value object as a JSON text column. Subclasses only name the type, so every JSON column
 * of the control plane is read and written the same way (unknown properties are ignored, which
 * keeps old rows readable after a field is removed).
 */
public abstract class JsonAttributeConverter<T> implements AttributeConverter<T, String> {

    private final Class<T> type;

    protected JsonAttributeConverter(Class<T> type) {
        this.type = type;
    }

    @Override
    public String convertToDatabaseColumn(T value) {
        return Json.write(value);
    }

    @Override
    public T convertToEntityAttribute(String json) {
        return Json.read(json, type);
    }
}
