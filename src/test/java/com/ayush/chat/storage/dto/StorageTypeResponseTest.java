package com.ayush.chat.storage.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StorageTypeResponseTest {

    @Test
    @DisplayName("should create via factory method")
    void shouldCreateViaFactoryMethod() {
        StorageTypeResponse response = StorageTypeResponse.of("mysql", "MySQL", "Relational SQL");

        assertEquals("mysql", response.getId());
        assertEquals("MySQL", response.getName());
        assertEquals("Relational SQL", response.getDescription());
    }

    @Test
    @DisplayName("should create via builder")
    void shouldCreateViaBuilder() {
        StorageTypeResponse response = StorageTypeResponse.builder()
                .id("mongodb")
                .name("MongoDB")
                .description("NoSQL/Document")
                .build();

        assertEquals("mongodb", response.getId());
        assertEquals("MongoDB", response.getName());
    }

    @Test
    @DisplayName("should support null values")
    void shouldSupportNullValues() {
        StorageTypeResponse response = StorageTypeResponse.of(null, null, null);

        assertNull(response.getId());
        assertNull(response.getName());
        assertNull(response.getDescription());
    }

    @Test
    @DisplayName("should support all-args constructor")
    void shouldSupportAllArgsConstructor() {
        StorageTypeResponse response = new StorageTypeResponse("h2", "H2", "Embedded SQL");

        assertEquals("h2", response.getId());
        assertEquals("H2", response.getName());
        assertEquals("Embedded SQL", response.getDescription());
    }
}
