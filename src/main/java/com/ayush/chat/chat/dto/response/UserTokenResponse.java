package com.ayush.chat.chat.dto.response;

import java.time.Instant;

public record UserTokenResponse(String token, Instant expiresAt, UserView user) {
}
