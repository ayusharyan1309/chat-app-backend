package com.ayush.chat.chat.webhook;

import com.ayush.chat.core.connector.config.ConnectorConfigService;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/** Webhook API representations. */
public final class WebhookViews {

    private WebhookViews() {
    }

    public record WebhookInput(String url, List<String> events, String description, Boolean active) {
    }

    /** {@code secret} is plaintext only in the response that creates the webhook; masked afterwards. */
    public record WebhookView(Long id, String url, Set<String> events, String description, boolean active, String secret,
                              Instant createdAt, Instant updatedAt) {

        static WebhookView of(Webhook w, String plaintextSecret) {
            return new WebhookView(w.getId(), w.getUrl(), w.getEventTypes(), w.getDescription(), w.isActive(),
                    plaintextSecret != null ? plaintextSecret : ConnectorConfigService.MASK, w.getCreatedAt(), w.getUpdatedAt());
        }
    }

    public record DeliveryView(Long id, Long webhookId, String eventId, String eventType, WebhookDelivery.Status status,
                               int attempts, Integer responseCode, String error, Long durationMs, Instant createdAt,
                               Instant lastAttemptAt) {

        static DeliveryView of(WebhookDelivery d) {
            return new DeliveryView(d.getId(), d.getWebhookId(), d.getEventId(), d.getEventType(), d.getStatus(),
                    d.getAttempts(), d.getResponseCode(), d.getError(), d.getDurationMs(), d.getCreatedAt(),
                    d.getLastAttemptAt());
        }
    }
}
