package com.ayush.chat.storage.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Database-agnostic representation of a conversation.
 * 
 * This DTO is used by all storage provider implementations.
 * It decouples the chat service layer from any specific database entity.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationData {

    /** Unique conversation identifier (String to support UUIDs from NoSQL). */
    private String id;

    /** First participant's user ID. */
    private String user1Id;

    /** First participant's email. */
    private String user1Email;

    /** Second participant's user ID. */
    private String user2Id;

    /** Second participant's email. */
    private String user2Email;

    /** Preview/summary of the last message in this conversation. */
    private String lastMessage;

    /** Timestamp of the last message. */
    private Instant lastMessageTime;

    /** 
     * Conversation status: 
     * - INIT: Created but not yet accepted
     * - ACTIVE: Both users can chat
     * - INACTIVE: Blocked or archived
     */
    @Builder.Default
    private String status = "INIT";

    /** Whether user1 has blocked user2. */
    @Builder.Default
    private boolean blockedByUser1 = false;

    /** Whether user2 has blocked user1. */
    @Builder.Default
    private boolean blockedByUser2 = false;

    /** Total number of messages in this conversation. */
    @Builder.Default
    private long messageCount = 0;

    /** Number of unread messages for user1. */
    @Builder.Default
    private int unreadCountUser1 = 0;

    /** Number of unread messages for user2. */
    @Builder.Default
    private int unreadCountUser2 = 0;

    /** Timestamp when the conversation was created. */
    private Instant createdAt;

    /** Timestamp when the conversation was last updated. */
    private Instant updatedAt;

    /** Metadata map for extensible properties. */
    private Map<String, Object> metadata;

    /** Tenant identifier for multi-tenant isolation. */
    private String tenantId;

    /**
     * Get the other participant's ID given one participant.
     */
    public String getOtherUserId(String userId) {
        if (userId.equals(user1Id)) return user2Id;
        if (userId.equals(user2Id)) return user1Id;
        return null;
    }

    /**
     * Get the other participant's email given one participant.
     */
    public String getOtherUserEmail(String userEmail) {
        if (userEmail.equals(user1Email)) return user2Email;
        if (userEmail.equals(user2Email)) return user1Email;
        return null;
    }

    /**
     * Check if a user is a participant in this conversation.
     */
    public boolean isParticipant(String userId) {
        return userId.equals(user1Id) || userId.equals(user2Id);
    }

    /**
     * Check if a user is blocked in this conversation.
     */
    public boolean isUserBlocked(String userId) {
        if (userId.equals(user1Id)) return blockedByUser2;
        if (userId.equals(user2Id)) return blockedByUser1;
        return false;
    }
}
