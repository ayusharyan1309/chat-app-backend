package com.ayush.chat.core.persistence;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CsvSetConverterTest {

    private final CsvSetConverter converter = new CsvSetConverter();

    @Test
    void keepsOrderAndIgnoresBlanks() {
        assertThat(converter.convertToEntityAttribute(" b, a,,c ")).containsExactly("b", "a", "c");
        assertThat(converter.convertToEntityAttribute(null)).isEmpty();
        assertThat(converter.convertToEntityAttribute("")).isEmpty();
        assertThat(converter.convertToDatabaseColumn(new LinkedHashSet<>(List.of("x", "y")))).isEqualTo("x,y");
        assertThat(converter.convertToDatabaseColumn(null)).isEmpty();
    }
}
