package com.ayush.chat.admin.dto.response;

import com.ayush.chat.auth.entity.AdminUser;

import java.time.Instant;

public record AdminView(Long id, String email, String name, AdminUser.Role role, Instant lastLoginAt, Instant createdAt) {
    public static AdminView of(AdminUser a) {
        return new AdminView(a.getId(), a.getEmail(), a.getName(), a.getRole(), a.getLastLoginAt(), a.getCreatedAt());
    }
}
