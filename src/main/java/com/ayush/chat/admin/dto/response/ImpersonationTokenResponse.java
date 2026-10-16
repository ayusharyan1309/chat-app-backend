package com.ayush.chat.admin.dto.response;

import com.ayush.chat.chat.dto.response.UserView;

import java.time.Instant;

public record ImpersonationTokenResponse(String token, Instant expiresAt, UserView user) {
}
