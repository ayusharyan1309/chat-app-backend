package com.ayush.chat.webhook.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "platform_webhook_deliveries")
public class WebhookDelivery {

    public enum Status { PENDING, SUCCESS, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "webhook_id", nullable = false)
    private Long webhookId;

    @Column(name = "event_id", nullable = false)
    private String eventId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private Status status;

    private int attempts;

    @Column(name = "response_code")
    private Integer responseCode;

    private String error;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;
}
