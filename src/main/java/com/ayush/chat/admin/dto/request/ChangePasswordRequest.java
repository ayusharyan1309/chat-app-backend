package com.ayush.chat.admin.dto.request;

public record ChangePasswordRequest(String currentPassword, String newPassword) {
}
