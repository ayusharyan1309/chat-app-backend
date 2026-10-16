package com.ayush.chat.auth.dto;

import com.ayush.chat.auth.entity.ApiKey;

/** {@code plaintext} is returned exactly once, at creation. */
public record CreatedApiKey(ApiKey key, String plaintext) {
}
