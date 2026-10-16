package com.ayush.chat.admin.dto.request;

import com.ayush.chat.auth.entity.AdminUser;

public record CreateAdminRequest(String email, String name, String password, AdminUser.Role role) {
}
