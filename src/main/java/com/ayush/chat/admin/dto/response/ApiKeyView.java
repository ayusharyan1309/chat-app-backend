package com.ayush.chat.admin.dto.response;

import com.ayush.chat.auth.entity.ApiKey;

import java.time.Instant;

/** {@code key} is the plaintext key, present only in the response that creates it. */
public record ApiKeyView(Long id, String name, String prefix, Instant createdAt, String createdBy, Instant lastUsedAt,
                         Instant revokedAt, String key) {
    public static ApiKeyView of(ApiKey k, String plaintext) {
        return new ApiKeyView(k.getId(), k.getName(), k.getKeyPrefix(), k.getCreatedAt(), k.getCreatedBy(),
                k.getLastUsedAt(), k.getRevokedAt(), plaintext);
    }
}
