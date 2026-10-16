package com.ayush.chat.chat.dto.response;

import java.time.Instant;

public record ServerTokenResponse(String token, Instant expiresAt) {
}
