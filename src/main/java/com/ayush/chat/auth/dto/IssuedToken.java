package com.ayush.chat.auth.dto;

import java.time.Instant;

public record IssuedToken(String token, Instant expiresAt) {
}
