package com.ayush.chat.storage.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for user data fetched from any platform database.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserResponse {

    private String id;
    private String email;
    private String fullName;
    private String avatarUrl;
    private String platformId;
    private String source;

    public static UserResponse fromSupabase(String id, String email, String fullName, String avatarUrl) {
        return UserResponse.builder()
                .id(id)
                .email(email)
                .fullName(fullName)
                .avatarUrl(avatarUrl)
                .source("supabase")
                .build();
    }

    public static UserResponse fromPlatform(String id, String email, String fullName, String platformId) {
        return UserResponse.builder()
                .id(id)
                .email(email)
                .fullName(fullName)
                .platformId(platformId)
                .source(platformId)
                .build();
    }
}
