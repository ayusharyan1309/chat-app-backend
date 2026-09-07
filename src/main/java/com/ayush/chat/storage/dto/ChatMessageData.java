package com.ayush.chat.storage.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * Database-agnostic representation of a chat message.
 * 
 * This DTO is used by all storage provider implementations.
 * It decouples the chat service layer from any specific database entity.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessageData {

    /** Unique message identifier (String to support UUIDs from NoSQL). */
    private String id;

    /** Conversation ID this message belongs to. */
    private String conversationId;

    /** Sender's user ID. */
    private String senderId;

    /** Sender's email (denormalized for quick lookup). */
    private String senderEmail;

    /** Sender's display name. */
    private String senderName;

    /** Receiver's user ID. */
    private String receiverId;

    /** Receiver's email (denormalized for quick lookup). */
    private String receiverEmail;

    /** The message content/text. */
    private String content;

    /** Message type: TEXT, IMAGE, FILE, VIDEO, AUDIO, SYSTEM */
    @Builder.Default
    private String messageType = "TEXT";

    /** URL for media attachments (images, files, etc.). */
    private String mediaUrl;

    /** MIME type of the media attachment. */
    private String mediaMimeType;

    /** File size in bytes (for media messages). */
    private Long mediaSize;

    /** Whether the message has been read by the receiver. */
    @Builder.Default
    private boolean read = false;

    /** Timestamp when the message was read. */
    private Instant readAt;

    /** Message status: SENT, DELIVERED, READ, FAILED */
    @Builder.Default
    private String status = "SENT";

    /** Whether the message was deleted (soft delete). */
    @Builder.Default
    private boolean deleted = false;

    /** Timestamp when the message was created. */
    private Instant createdAt;

    /** Timestamp when the message was last updated. */
    private Instant updatedAt;

    /** Metadata map for extensible properties (reactions, threads, etc.). */
    private Map<String, Object> metadata;

    /** Tenant identifier for multi-tenant isolation. */
    private String tenantId;
}
