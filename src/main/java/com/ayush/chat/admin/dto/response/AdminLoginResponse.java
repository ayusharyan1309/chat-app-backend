package com.ayush.chat.admin.dto.response;

import java.time.Instant;

public record AdminLoginResponse(String token, Instant expiresAt, AdminView admin) {
}
