package com.ayush.chat.webhook.dto;

import com.ayush.chat.webhook.entity.WebhookDelivery;

import java.time.Instant;

public record WebhookDeliveryView(Long id, Long webhookId, String eventId, String eventType, WebhookDelivery.Status status,
                                  int attempts, Integer responseCode, String error, Long durationMs, Instant createdAt,
                                  Instant lastAttemptAt) {

    public static WebhookDeliveryView of(WebhookDelivery d) {
        return new WebhookDeliveryView(d.getId(), d.getWebhookId(), d.getEventId(), d.getEventType(), d.getStatus(),
                d.getAttempts(), d.getResponseCode(), d.getError(), d.getDurationMs(), d.getCreatedAt(),
                d.getLastAttemptAt());
    }
}
