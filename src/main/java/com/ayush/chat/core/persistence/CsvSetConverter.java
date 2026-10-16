package com.ayush.chat.core.persistence;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/** An ordered set of identifiers stored as one comma-separated column. */
@Converter
public class CsvSetConverter implements AttributeConverter<Set<String>, String> {

    @Override
    public String convertToDatabaseColumn(Set<String> values) {
        return values == null ? "" : String.join(",", values);
    }

    @Override
    public Set<String> convertToEntityAttribute(String column) {
        if (column == null || column.isBlank()) {
            return new LinkedHashSet<>();
        }
        return Arrays.stream(column.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
