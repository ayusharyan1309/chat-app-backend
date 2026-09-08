package com.ayush.chat.storage.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for available storage types.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StorageTypeResponse {

    private String id;
    private String name;
    private String description;

    public static StorageTypeResponse of(String id, String name, String description) {
        return StorageTypeResponse.builder()
                .id(id)
                .name(name)
                .description(description)
                .build();
    }
}
