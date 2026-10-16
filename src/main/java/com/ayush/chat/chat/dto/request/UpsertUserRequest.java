package com.ayush.chat.chat.dto.request;

import java.util.Map;

public record UpsertUserRequest(String id, String displayName, String email, String phone, String avatarUrl, String about,
                                Map<String, Object> metadata) {
}
