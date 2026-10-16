package com.ayush.chat.auth.dto;

import com.ayush.chat.auth.entity.AdminUser;

public record LoginResult(IssuedToken token, AdminUser admin) {
}
