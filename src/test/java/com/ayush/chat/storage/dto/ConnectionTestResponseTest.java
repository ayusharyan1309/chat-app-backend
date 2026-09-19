package com.ayush.chat.storage.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConnectionTestResponseTest {

    @Test
    @DisplayName("should create success response")
    void shouldCreateSuccessResponse() {
        ConnectionTestResponse response = ConnectionTestResponse.success("H2", "Connection successful");

        assertTrue(response.isHealthy());
        assertEquals("H2", response.getType());
        assertEquals("Connection successful", response.getMessage());
    }

    @Test
    @DisplayName("should create failure response")
    void shouldCreateFailureResponse() {
        ConnectionTestResponse response = ConnectionTestResponse.failure("MySQL", "Connection refused");

        assertFalse(response.isHealthy());
        assertEquals("MySQL", response.getType());
        assertEquals("Connection refused", response.getMessage());
    }

    @Test
    @DisplayName("should support builder pattern")
    void shouldSupportBuilder() {
        ConnectionTestResponse response = ConnectionTestResponse.builder()
                .healthy(true)
                .type("MongoDB")
                .message("All good")
                .build();

        assertTrue(response.isHealthy());
        assertEquals("MongoDB", response.getType());
    }

    @Test
    @DisplayName("should support all-args constructor")
    void shouldSupportAllArgsConstructor() {
        ConnectionTestResponse response = new ConnectionTestResponse(false, "Firebase", "Error");

        assertFalse(response.isHealthy());
        assertEquals("Firebase", response.getType());
        assertEquals("Error", response.getMessage());
    }

    @Test
    @DisplayName("should handle null values")
    void shouldHandleNullValues() {
        ConnectionTestResponse response = ConnectionTestResponse.success(null, null);

        assertTrue(response.isHealthy());
        assertNull(response.getType());
        assertNull(response.getMessage());
    }
}
