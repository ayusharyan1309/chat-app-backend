package com.ayush.chat.storage.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for connection test results.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConnectionTestResponse {

    private boolean healthy;
    private String type;
    private String message;

    public static ConnectionTestResponse success(String type, String message) {
        return ConnectionTestResponse.builder()
                .healthy(true)
                .type(type)
                .message(message)
                .build();
    }

    public static ConnectionTestResponse failure(String type, String message) {
        return ConnectionTestResponse.builder()
                .healthy(false)
                .type(type)
                .message(message)
                .build();
    }
}
