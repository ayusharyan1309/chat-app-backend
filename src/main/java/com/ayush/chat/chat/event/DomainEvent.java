package com.ayush.chat.chat.event;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Durable domain event published to the work-queue topic and consumed by webhooks and push.
 * {@link #recipients} and the other routing hints are internal and never sent to webhooks.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DomainEvent(
        String id,
        String type,
        String tenant,
        Instant createdAt,
        Object data,
        List<String> recipients,
        String senderId,
        String senderName,
        String conversationTitle) {

    public static final String MESSAGE_CREATED = "message.created";
    public static final String MESSAGE_UPDATED = "message.updated";
    public static final String MESSAGE_DELETED = "message.deleted";
    public static final String CONVERSATION_CREATED = "conversation.created";
    public static final String MEMBER_ADDED = "conversation.member_added";
    public static final String MEMBER_REMOVED = "conversation.member_removed";
    public static final String PING = "webhook.ping";

    public static final List<String> WEBHOOK_TYPES = List.of(MESSAGE_CREATED, MESSAGE_UPDATED, MESSAGE_DELETED,
            CONVERSATION_CREATED, MEMBER_ADDED, MEMBER_REMOVED);

    /** Public webhook payload (routing hints stripped). */
    @JsonIgnore
    public WebhookPayload toWebhookPayload() {
        return new WebhookPayload(id, type, tenant, createdAt, data);
    }

    public record WebhookPayload(String id, String type, String tenant, Instant createdAt, Object data) {
    }
}
