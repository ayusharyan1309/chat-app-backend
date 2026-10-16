package com.ayush.chat.webhook.dto;

import com.ayush.chat.core.connector.config.ConnectorConfigService;
import com.ayush.chat.webhook.entity.Webhook;

import java.time.Instant;
import java.util.Set;

/** {@code secret} is plaintext only in the response that creates the webhook; masked afterwards. */
public record WebhookView(Long id, String url, Set<String> events, String description, boolean active, String secret,
                          Instant createdAt, Instant updatedAt) {

    public static WebhookView of(Webhook w, String plaintextSecret) {
        return new WebhookView(w.getId(), w.getUrl(), w.getEventTypes(), w.getDescription(), w.isActive(),
                plaintextSecret != null ? plaintextSecret : ConnectorConfigService.MASK, w.getCreatedAt(), w.getUpdatedAt());
    }
}
